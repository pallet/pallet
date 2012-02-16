(ns pallet.local.execute
  "Local execution of pallet actions"
  (:require
   [clojure.string :as string]
   [clojure.java.io :as io]
   [clojure.tools.logging :as logging]
   [pallet.compute.jvm :as jvm]
   [pallet.execute :as execute]
   [pallet.local.transport :as transport]
   [pallet.script :as script]
   [pallet.script-builder :as script-builder]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]))

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

(defn bash-on-origin
  "Execute a bash action on the origin"
  [session action action-type value]
  (logging/trace "bash-on-origin")
  (let [script (script-builder/build-script value action action-type)
        tmpfile (java.io.File/createTempFile "pallet" "script")]
    (try
      (logging/tracef "bash-on-origin script\n%s" script)
      (spit tmpfile script)
      (let [result (transport/exec
                    {:execv ["/bin/chmod" "+x" (.getPath tmpfile)]}
                    nil)]
        (when-not (zero? (:exit result))
          (logging/warnf
           "bash-on-origin: Could not chmod script file: %s"
           (:out result))))
      (let [cmd (script-builder/build-code
                 session action action-type (.getPath tmpfile))
            result (transport/exec cmd {:output-f #(logging/spy %)})
            [result session] (execute/parse-shell-result session result)]
        (verify-sh-return "for origin cmd" value result)
        [result session])
      (finally  (.delete tmpfile)))))

(defn clojure-on-origin
  "Execute a clojure function on the origin"
  [session f]
  (logging/debugf "clojure-on-origin %s" f)
  (f session))

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
    (transport/exec {:in (stevedore/script ~@body)} nil)))

(defmacro local-checked-script
  "Run a script on the local machine, setting up stevedore to produce the
   correct target specific code.  The return code is checked."
  [msg & body]
  `(local-script-context
    (let [cmd# (stevedore/checked-script ~msg ~@body)]
      (verify-sh-return
       ~msg cmd#
       (transport/exec {:in cmd#} nil)))))

(defn local-script-expand
  "Expand a script expression."
  [expr]
  (string/trim (:out (local-script (echo ~expr)))))
