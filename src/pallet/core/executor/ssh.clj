(ns pallet.core.executor.ssh
  "An action executor over ssh"
  (:require
   [clojure.tools.logging :as logging]
   [pallet.action :refer [implementation]]
   [pallet.actions.direct :refer [direct-script]]
   [pallet.core.executor.protocols :refer :all]
   [pallet.core.node :as node]
   [pallet.core.script-state :as script-state :refer [update-node-state]]
   [pallet.execute :refer [result-with-error-map]]
   [pallet.exception :refer [domain-info]]
   [pallet.ssh.execute :as ssh]
   [pallet.local.execute :as local]
   [pallet.target :refer [primary-ip]]
   [pallet.transport :as transport]
   [pallet.user :refer [user?]]))

(defn execute-ssh
  [transport node action value]
  (ssh/ssh-script-on-target transport node (:user action) action value))

(defn execute-local
  [node action value]
  (local/script-on-origin (:user action) action value))

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
      (logging/debugf "options %s" (pr-str (:options action)))
      (case (:action-type metadata :script)
        :script (let [{:keys [exit err out] :as result}
                      (if (= :target (:location metadata :target))
                        (execute-ssh transport node action value)
                        (execute-local node action value))
                      result (merge result (select-keys metadata [:summary]))]
                  (when out
                    (swap! state update-node-state (node/id node) out))
                  (when (and exit (pos? exit)
                             (:error-on-non-zero-exit (:options action) true))
                    (let [result (result-with-error-map
                                  (primary-ip target) "Error executing script"
                                  result)]
                      (throw
                       (domain-info
                        (str "Action failed: " err)
                        {:result result}))))
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
