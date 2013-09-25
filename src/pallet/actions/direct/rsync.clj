(ns pallet.actions.direct.rsync
  (:require
   [clojure.tools.logging :as logging]
   [pallet.action :refer [implement-action]]
   [pallet.actions :refer [rsync]]
   [pallet.crate :refer [admin-user target-node]]
   [pallet.node :refer [primary-ip ssh-port]]
   [pallet.stevedore :as stevedore]))

(def ^{:private true}
  cmd "/usr/bin/rsync -e '%s' -F -F %s %s %s@%s:%s")

(def default-options {:r true :delete true :copy-links true
                      :rsync-path "sudo rsync"
                      :owner true
                      :perms true})

(implement-action rsync :direct {:action-type :script :location :origin}
  [from to {:keys [ip username port] :as options}]
  (logging/debugf "rsync %s to %s" from to)
  (let [extra-options (dissoc options :port)
        ssh (str "/usr/bin/ssh -o \"StrictHostKeyChecking no\" "
                 (if-let [port (or port (ssh-port (target-node)))]
                   (format "-p %s" port)))
        cmd (format
             cmd ssh
             (stevedore/map-to-arg-string (merge default-options extra-options))
             from
             (or username (:username (admin-user)))
             (or ip (primary-ip (target-node)))
             to)]
    [{:language :bash}
     (stevedore/checked-commands (format "rsync %s to %s" from to) cmd)]))
