(ns pallet.local.execute
  "Local execution of pallet actions"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.compute.jvm :as jvm]
   [pallet.execute :as execute
    :refer [log-script-output result-with-error-map status-lines]]
   [pallet.script :as script]
   [pallet.script-builder :as script-builder]
   [pallet.stevedore :as stevedore]
   [pallet.transport :as transport]
   [pallet.transport.local]
   [pallet.utils :refer [log-multiline]]))

(def local-connection
  (transport/open (transport/factory :local {}) nil nil))

(defn verify-sh-return
  "Verify the return code of a sh execution"
  [msg cmd result]
  (if (zero? (:exit result))
    result
    (assoc result
      :error {:message (format
                        "localhost Error executing script %s :out %s :err %s"
                        msg
                        (string/join ", " (status-lines (:out result)))
                        (:err result))
              :type :pallet-script-excution-error
              :script-exit (:exit result)
              :script-out  (:out result)
              :script-err (:err result)
              :server "localhost"})))


(defn build-code
  "Build the code with defaults for local execution."
  [session action ^java.io.File tmpfile]
  (script-builder/build-code
   session
   (assoc action :default-script-prefix :no-sudo)
   (.getPath tmpfile)))

(defn- local-script-dir [{:keys [script-dir]}]
  (if script-dir
    (if (.startsWith "/" script-dir)
      script-dir
      (str (System/getProperty "user.home") "/" script-dir))
    (System/getProperty "user.home")))

(defn script-on-origin
  "Execute a script action on the origin"
  [session action action-type [options value]]
  (logging/trace "script-on-origin")
  (let [action (merge {:script-dir (local-script-dir action)} action)
        script (script-builder/build-script options value action)
        tmpfile (java.io.File/createTempFile "pallet" "script")]
    (try
      (log-multiline :debug " localhost ==> %s"
                     (str " -----------------------------------------\n"
                          script
                          "\n------------------------------------------"))
      (spit tmpfile script)
      (let [result (transport/exec
                    local-connection
                    {:execv ["/bin/chmod" "+x" (.getPath tmpfile)]}
                    nil)]
        (when-not (zero? (:exit result))
          (logging/warnf
           "script-on-origin: Could not chmod script file: %s"
           (:out result))))
      (logging/debugf "localhost <== ----------------------------------------")
      (let [cmd (build-code session action tmpfile)
            _ (logging/debugf "localhost %s" cmd)
            result (transport/exec
                    local-connection cmd
                    {:output-f (log-script-output "localhost" nil)})
            [result session] (execute/parse-shell-result session result)
            result (assoc result :script script)]
        (when-let [e (:err result)]
          (when-not (string/blank? e)
            (doseq [^String l (string/split-lines e)
                    :when (not (.startsWith l "#> "))] ; logged elsewhere
              (logging/warnf "localhost %s" l))))
        (logging/debugf
         "localhost <== ----------------------------------------")
        [(result-with-error-map "localhost" "Error executing script" result)
         session])
      (finally (.delete tmpfile)))))

(defn clojure-on-origin
  "Execute a clojure function on the origin"
  [session {:keys [script-dir] :as action} f]
  (logging/debugf "clojure-on-origin %s" f)
  (f (assoc session :script-dir script-dir)))

(defmacro local-script-context
  "Run a script on the local machine, setting up stevedore to produce the
   correct target specific code"
  [& body]
  `(script/with-script-context [(jvm/os-family)]
     (stevedore/with-script-language :pallet.stevedore.bash/bash
       ~@body)))

(defmacro local-script
  "Run a script on the local machine, setting up stevedore to produce the
   correct target specific code"
  [& body]
  `(local-script-context
    (logging/debugf "local-script %s" (stevedore/script ~@body))
    (transport/exec local-connection {:in (stevedore/script ~@body)} nil)))

(defmacro local-checked-script
  "Run a script on the local machine, setting up stevedore to produce the
   correct target specific code.  The return code is checked."
  [msg & body]
  `(local-script-context
    (let [cmd# (stevedore/checked-script ~msg ~@body)]
      (result-with-error-map "localhost" ~msg
        (transport/exec local-connection {:in cmd#} nil)))))

(defn local-script-expand
  "Expand a script expression."
  [expr]
  (string/trim (:out (local-script (println ~expr)))))
