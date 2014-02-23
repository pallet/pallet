(ns pallet.core.executor.ssh
  "An action executor over ssh"
  (:require
   [clojure.tools.logging :as logging]
   [pallet.action :refer [implementation]]
   pallet.actions.direct
   [pallet.core.executor.protocols :refer :all]
   [pallet.ssh.execute :as ssh]
   [pallet.transport :as transport]
   [pallet.user :refer [user?]]))

(defn direct-script
  "Execute the direct action implementation, which returns script or other
  argument data, and metadata."
  [{:keys [options args] :as action}]
  (let [{:keys [metadata f]} (implementation action :direct)
        script-vec (apply f options args)]
    (logging/tracef "direct-script %s %s" f (vec args))
    (logging/tracef "direct-script %s" script-vec)
    script-vec))

(deftype SshActionExecutor [transport]
  ActionExecutor
  (execute [executor target action]
    {:pre [(:node target)(map? action)]}
    (let [script (direct-script action)]
      (ssh/ssh-script-on-target
       transport (:node target) (:user action) action script))))

(defn ssh-executor
  []
  (SshActionExecutor. (transport/factory :ssh {})))
