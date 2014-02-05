(ns pallet.core.executor.ssh
  "An action executor over ssh"
  (:require
   [clojure.tools.logging :as logging]
   [pallet.action :refer [implementation]]
   [pallet.core.executor.protocols :refer :all]
   [pallet.ssh.execute :as ssh]))

(defn direct-script
  "Execute the direct action implementation, which returns script or other
  argument data, and metadata."
  [{:keys [args script-dir] :as action}]
  (let [{:keys [metadata f]} (implementation action :direct)
        {:keys [action-type location]} metadata
        script-vec (apply f (drop 1 args))]
    (logging/tracef "direct-script %s %s" f (vec args))
    (logging/tracef "direct-script %s" script-vec)
    [script-vec action-type location]))

(deftype SshActionExecutor [result-chan]
  ActionExecutor
  (execute [executor node user action]
    (let [[script action-type location] (direct-script action)]
      (ssh/ssh-script-on-target node user action script))))

(defn ssh-executor
  []
  (SshActionExecutor. nil))
