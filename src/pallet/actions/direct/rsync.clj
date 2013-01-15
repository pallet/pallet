(ns pallet.actions.direct.rsync
  (:require
   [pallet.context :as context]
   [pallet.execute :as execute]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [clojure.tools.logging :as logging])
  (:use
   [pallet.action :only [implement-action]]
   [pallet.actions :only [rsync]]
   [pallet.core.session :only [admin-user target-ip]]
   [pallet.node :only [primary-ip]]))

(def ^{:private true}
  cmd "/usr/bin/rsync -e '%s' -r --delete --copy-links -F -F%s %s %s@%s:%s")

(implement-action rsync :direct
                  {:action-type :script :location :origin}
                  [session from to {:keys [port] :as options}]
  (logging/debugf "rsync %s to %s" from to)
  (let [extra-options (dissoc options :port)
        ssh (str "/usr/bin/ssh -o \"StrictHostKeyChecking no\" "
                 (if port (format "-p %s" port)))
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
