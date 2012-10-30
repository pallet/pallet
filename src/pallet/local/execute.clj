(ns pallet.local.execute
  "Local execution of pallet actions"
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.tools.logging :as logging]
   [pallet.compute.jvm :as jvm]
   [pallet.execute :as execute]
   [pallet.transport :as transport]
   [pallet.transport.local]
   [pallet.script :as script]
   [pallet.script-builder :as script-builder]
   [pallet.stevedore :as stevedore]))

(def local-connection
  (transport/open (transport/factory :local {}) nil nil nil))

(defn verify-sh-return
  "Verify the return code of a sh execution"
  [msg cmd result]
  (if (zero? (:exit result))
    result
    (assoc result
      :error {:message (format
                        "Error executing script %s\n :cmd %s :out %s\n :err %s"
                        msg cmd (:out result) (:err result))
              :type :pallet-script-excution-error
              :script-exit (:exit result)
              :script-out  (:out result)
              :script-err (:err result)
              :server "localhost"})))

(defn script-on-origin
  "Execute a script action on the origin"
  [session action action-type [options value]]
  (logging/trace "script-on-origin")
  (let [script (script-builder/build-script options value action)
        tmpfile (java.io.File/createTempFile "pallet" "script")]
    (try
      (logging/debugf "script-on-origin script\n%s" script)
      (spit tmpfile script)
      (let [result (transport/exec
                    local-connection
                    {:execv ["/bin/chmod" "+x" (.getPath tmpfile)]}
                    nil)]
        (when-not (zero? (:exit result))
          (logging/warnf
           "script-on-origin: Could not chmod script file: %s"
           (:out result))))
      (let [cmd (script-builder/build-code session action (.getPath tmpfile))
            result (transport/exec
                    local-connection cmd {:output-f #(logging/spy %)})
            [result session] (execute/parse-shell-result session result)
            result (assoc result :script script)]
        (verify-sh-return "for origin cmd" value result)
        [result session])
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
    (logging/infof "local-script %s" (stevedore/script ~@body))
    (transport/exec local-connection {:in (stevedore/script ~@body)} nil)))

(defmacro local-checked-script
  "Run a script on the local machine, setting up stevedore to produce the
   correct target specific code.  The return code is checked."
  [msg & body]
  `(local-script-context
    (let [cmd# (stevedore/checked-script ~msg ~@body)]
      (verify-sh-return
       ~msg cmd#
       (transport/exec local-connection {:in cmd#} nil)))))

(defn local-script-expand
  "Expand a script expression."
  [expr]
  (string/trim (:out (local-script (echo ~expr)))))
