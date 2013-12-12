(ns pallet.echo.execute
  "Action execution that just echos the action script"
  (:require
   [clojure.tools.logging :as logging]))

(defn echo-bash
  "Echo a bash action. Do not execute."
  [session script]
  (logging/tracef "echo-bash %s" script)
  [script session])

(defn echo-clojure
  "Echo a clojure action (which returns nil)"
  [session f]
  (logging/trace "echo-clojure")
  (f session))

(defn echo-transfer
  "echo transfer of files"
  [session value]
  (logging/trace "Local transfer")
  (let [[from to] value]
    (logging/debugf "Copying %s to %s" from to))
  [value session])
