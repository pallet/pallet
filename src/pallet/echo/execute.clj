(ns pallet.echo.execute
  "Action execution that just echos the action script"
  (:require
   [clojure.tools.logging :as logging]))

(defn echo-bash
  "Echo a bash action. Do not execute."
  [session script]
  (logging/tracef "echo-bash %s" script)
  [{:script-options (first script)
    :script (second script)}
   session])

(defn echo-transfer
  "echo transfer of files"
  [session value action-type]
  (logging/trace "Local transfer")
  (doseq [{:keys [remote-path local-path]} value]
    (logging/debugf
     "Copying %s local-path %s remote-path %s"
     action-type local-path remote-path))
  [value session])
