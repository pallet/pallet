(ns pallet.actions.direct.rsync
  (:require
   [pallet.context :as context]
   [pallet.execute :as execute]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [clojure.tools.logging :as logging])
  (:use
   [pallet.action :only [implement-action]]
   [pallet.actions :only [rsync]]
   [pallet.monad :only [phase-pipeline]]
   [pallet.node :only [primary-ip]]))

(def ^{:private true}
  cmd "/usr/bin/rsync -e '%s' -rP --delete --copy-links -F -F %s %s@%s:%s")

(implement-action rsync :direct
  {:action-type :script :location :origin}
  [session from to {:keys [port]}]
  (logging/infof "rsync %s to %s" from to)
  (let [ssh (str "/usr/bin/ssh -o \"StrictHostKeyChecking no\" "
                 (if port (format "-p %s" port)))
        cmd (format
             cmd ssh from (:username (first (session/admin-user session)))
             (primary-ip (first (session/target-node session))) to)]
    [[{:language :bash}
      (stevedore/checked-commands (format "rsync %s to %s" from to) cmd)]
     session]))
