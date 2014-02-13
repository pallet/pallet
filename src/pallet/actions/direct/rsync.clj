(ns pallet.actions.direct.rsync
  (:require
   [clojure.tools.logging :as logging]
   [pallet.action :refer [implement-action]]
   [pallet.actions :refer [rsync*]]
   [pallet.node :refer [primary-ip ssh-port]]
   [pallet.stevedore :as stevedore]))

(def ^{:private true}
  cmd "/usr/bin/rsync -e '%s' -F -F %s %s %s@%s:%s")

(def default-options {:r true :delete true :copy-links true
                      :rsync-path "sudo rsync"
                      :owner true
                      :perms true})

(implement-action rsync* :direct {:action-type :script :location :origin}
  [action-options from to {:keys [ip username port] :as options}]
  (logging/debugf "rsync %s to %s" from to)
  (let [extra-options (dissoc options :port)
        ssh (str "/usr/bin/ssh -o \"StrictHostKeyChecking no\" "
                 (if port
                   (format "-p %s" port)))
        cmd (format
             cmd ssh
             (stevedore/map-to-arg-string (merge default-options extra-options))
             from username ip to)]
    [{:language :bash}
     (stevedore/checked-commands (format "rsync %s to %s" from to) cmd)]))
