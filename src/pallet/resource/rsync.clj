(ns pallet.resource.rsync
  (:require
   [pallet.compute :as compute]
   [pallet.resource :as resource]
   [pallet.resource.directory :as directory]
   [pallet.resource.package :as package]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target]
   [pallet.execute :as execute]
   [pallet.utils :as utils]
   [clojure.contrib.logging :as logging]))

(def cmd "/usr/bin/rsync -e '%s' -rP --delete --copy-links -F -F %s %s@%s:%s")

(resource/deflocal rsync
  (rsync*
   [request from to {:keys [port]}]
   (logging/info (format "rsync %s to %s" from to))
   (let [ssh (str "/usr/bin/ssh -o \"StrictHostKeyChecking no\" "
                  (if port (format "-p %s" port)))
         cmd (format
              cmd ssh from (:username utils/*admin-user*)
              (compute/primary-ip (:target-node request)) to)]
     (execute/sh-script cmd)
     request)))

(defn rsync-directory
  "Rsync from a local directory to a remote directory."
  [request from to & {:keys [owner group mode port] :as options}]
  (->
   request
   (package/package "rsync")
   (directory/directory to :owner owner :group group :mode mode)
   (rsync from to options)))
