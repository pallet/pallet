(ns pallet.core.executor.ssh
  "An action executor over ssh"
  (:require
   [clojure.tools.logging :as logging]
   [pallet.action :refer [implementation]]
   pallet.actions.direct
   [pallet.core.executor.protocols :refer :all]
   [pallet.ssh.execute :as ssh]
   [pallet.user :refer [user?]]))

(defn direct-script
  "Execute the direct action implementation, which returns script or other
  argument data, and metadata."
  [{:keys [action-options args script-dir] :as action}]
  (let [{:keys [metadata f]} (implementation action :direct)
        {:keys [action-type location]} metadata
        script-vec (apply f action-options (drop 1 args))]
    (logging/tracef "direct-script %s %s" f (vec args))
    (logging/tracef "direct-script %s" script-vec)
    [script-vec action-type location]))

(deftype SshActionExecutor [result-chan]
  ActionExecutor
  (execute [executor target user action]
    {:pre [(user? user)
           (:node target)]}
    (let [[script action-type location] (direct-script action)]
      (ssh/ssh-script-on-target (:node target) user action script))))

(defn ssh-executor
  []
  (SshActionExecutor. nil))
