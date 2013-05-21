(ns pallet.actions.direct.rsync
  (:require
   [clojure.tools.logging :as logging]
   [pallet.action :refer [implement-action]]
   [pallet.actions :refer [rsync]]
   [pallet.crate :refer [target-node]]
   [pallet.core.session :refer [admin-user target-ip]]
   [pallet.node :refer [ssh-port]]
   [pallet.stevedore :as stevedore]))

(def ^{:private true}
  cmd "/usr/bin/rsync -e '%s' -r --delete --copy-links -F -F%s %s %s@%s:%s")

(implement-action rsync :direct
                  {:action-type :script :location :origin}
                  [session from to {:keys [port] :as options}]
  (logging/debugf "rsync %s to %s" from to)
  (let [extra-options (dissoc options :port)
        ssh (str "/usr/bin/ssh -o \"StrictHostKeyChecking no\" "
                 (if-let [port (or port (ssh-port (target-node)))]
                   (format "-p %s" port)))
        cmd (format
             cmd ssh
             (if (seq extra-options)
               (str " " (stevedore/map-to-arg-string extra-options))
               "")

             from (:username (admin-user session))
             (target-ip session) to)]
    [[{:language :bash}
      (stevedore/checked-commands (format "rsync %s to %s" from to) cmd)]
     session]))
