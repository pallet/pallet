(ns pallet.core.executor.ssh
  "An action executor over ssh"
  (:require
   [clojure.tools.logging :as logging]
   [pallet.action :refer [implementation]]
   [pallet.actions.direct :refer [direct-script]]
   [pallet.core.executor.protocols :refer :all]
   [pallet.core.script-state :refer [node-state update-node-state]]
   [pallet.ssh.execute :as ssh]
   [pallet.transport :as transport]
   [pallet.user :refer [user?]]))

(deftype SshActionExecutor [transport state]
  ActionExecutor
  (execute [executor target action]
    {:pre [(:node target)(map? action)]}
    (let [node (:node target)
          script (direct-script action (node-state @state node))
          {:keys [out] :as result} (ssh/ssh-script-on-target
                                    transport (:node target)
                                    (:user action) action script)]
      (when out
        (swap! state update-node-state node out))
      result)))

(defn ssh-executor
  []
  (SshActionExecutor. (transport/factory :ssh {}) (atom {})))
