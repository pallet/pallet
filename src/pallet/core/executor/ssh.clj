(ns pallet.core.executor.ssh
  "An action executor over ssh"
  (:require
   [clojure.tools.logging :as logging]
   [pallet.action :refer [implementation]]
   [pallet.actions.direct :refer [direct-script]]
   [pallet.core.executor.protocols :refer :all]
   [pallet.core.node :as node]
   [pallet.core.script-state :as script-state :refer [update-node-state]]
   [pallet.ssh.execute :as ssh]
   [pallet.transport :as transport]
   [pallet.user :refer [user?]]))

(deftype SshActionExecutor [transport state]
  ActionExecutor
  (execute [executor target action]
    {:pre [(:node target)(map? action)]}
    (let [node (:node target)
          [metadata value] (direct-script
                             action
                             (script-state/node-state @state (node/id node)))]
      (logging/debugf "metadata %s" (pr-str metadata))
      (logging/debugf "value %s" (pr-str value))
      (case (:action-type metadata :script)
        :script (let [{:keys [out] :as result} (ssh/ssh-script-on-target
                                                transport (:node target)
                                                (:user action) action
                                                value)]
                  (when out
                    (swap! state update-node-state (node/id node) out))
                  result)
        :transfer/from-local {:return-value ((:f value) target)}
        :transfer/to-local (ssh/ssh-to-local
                            transport (:node target) (:user action)
                            value))))

  ActionExecutorState
  (node-state [executor node]
    (script-state/node-state @state (node/id node))))

(defn ssh-executor
  []
  (SshActionExecutor. (transport/factory :ssh {}) (atom {})))
