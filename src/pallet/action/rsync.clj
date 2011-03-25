(ns pallet.action.rsync
  (:require
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.package :as package]
   [pallet.compute :as compute]
   [pallet.execute :as execute]
   [pallet.session :as session]
   [pallet.target :as target]
   [pallet.utils :as utils]
   [clojure.contrib.logging :as logging]))

(def cmd "/usr/bin/rsync -e '%s' -rP --delete --copy-links -F -F %s %s@%s:%s")

(action/def-clj-action rsync
  [session from to {:keys [port]}]
  (logging/info (format "rsync %s to %s" from to))
  (let [ssh (str "/usr/bin/ssh -o \"StrictHostKeyChecking no\" "
                 (if port (format "-p %s" port)))
        cmd (format
             cmd ssh from (:username utils/*admin-user*)
             (compute/primary-ip (session/target-node session)) to)]
    (execute/sh-script cmd)
    session))

(defn rsync-directory
  "Rsync from a local directory to a remote directory."
  [session from to & {:keys [owner group mode port] :as options}]
  (->
   session
   (package/package "rsync")
   (directory/directory to :owner owner :group group :mode mode)
   (rsync from to options)))
