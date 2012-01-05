(ns pallet.echo.execute
  "Action execution that just echos the action script"
  (:require
   [clojure.tools.logging :as logging]))

(defn echo-bash
  "Echo a bash action. Do not execute."
  [session {:keys [f] :as action}]
  (logging/trace "echo-bash")
  (let [{:keys [value session]} (f session)]
    [value session]))

(defn echo-clojure
  "Echo a clojure action (which returns nil)"
  [session {:keys [f] :as action}]
  (logging/trace "echo-clojure")
  (let [{:keys [value session]} (f session)]
    ["" session]))

(defn echo-transfer
  "echo transfer of files"
  [session {:keys [f] :as action}]
  (let [{:keys [value session]} (f session)]
    (logging/trace "Local transfer")
    (doseq [[from to] value]
      (logging/debugf "Copying %s to %s" from to))
    [value session]))
