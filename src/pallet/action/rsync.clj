(ns pallet.action.rsync
  (:require
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.package :as package]
   [pallet.execute :as execute]
   [pallet.node :as node]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [clojure.tools.logging :as logging]))

(def cmd "/usr/bin/rsync -e '%s' -rP --delete --copy-links -F -F%s %s %s@%s:%s")

(defn rsync-command
  [session from to {:keys [port] :as options}]
  (logging/infof "rsync %s to %s" from to)
  (let [extra-options (dissoc options :port)
        ssh (str "/usr/bin/ssh -o \"StrictHostKeyChecking no\" "
                 (if-let [port (or port
                                   (node/ssh-port
                                    (session/target-node session)))]
                   (format "-p %s" port)))]
    (format
     cmd
     ssh
     (if (seq extra-options)
       (str " " (stevedore/map-to-arg-string extra-options))
       "")
     from
     (:username (session/admin-user session))
     (node/primary-ip (session/target-node session)) to)))

(action/def-clj-action rsync
  [session from to {:keys [port] :as options}]
  (logging/infof "rsync %s to %s" from to)
  (execute/sh-script (rsync-command session from to options))
  session)

(defn rsync-directory
  "Rsync from a local directory to a remote directory."
  [session from to & {:keys [owner group mode port] :as options}]
  (->
   session
   (package/package "rsync")
   (directory/directory to :owner owner :group group :mode mode)
   (rsync from to options)))
