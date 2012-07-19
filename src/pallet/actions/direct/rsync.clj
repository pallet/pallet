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
   [pallet.monad :only [phase-pipeline]]
   [pallet.node :only [primary-ip]]))

(def ^{:private true}
  cmd "/usr/bin/rsync -e '%s' -rP --delete --copy-links -F -F %s %s@%s:%s")

(implement-action rsync :direct
  {:action-type :script :location :origin}
  [session from to {:keys [port]}]
  (logging/debugf "rsync %s to %s" from to)
  (let [ssh (str "/usr/bin/ssh -o \"StrictHostKeyChecking no\" "
                 (if port (format "-p %s" port)))
        cmd (format
             cmd ssh from (:username (admin-user session))
             (target-ip session) to)]
    [[{:language :bash}
      (stevedore/checked-commands (format "rsync %s to %s" from to) cmd)]
     session]))
