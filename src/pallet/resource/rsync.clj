(ns pallet.resource.rsync
  (:require
   [pallet.compute :as compute]
   [pallet.resource :as resource]
   [pallet.resource.directory :as directory]
   [pallet.resource.package :as package]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target]
   [pallet.utils :as utils]
   [clojure.contrib.logging :as logging]))

(def cmd "/usr/bin/rsync -e '%s' -rP --delete --copy-links -F -F %s %s@%s:%s")

(defn rsync*
  [from to options]
  (logging/info (format "rsync %s to %s" from to))
  (let [ssh (str "/usr/bin/ssh -o \"StrictHostKeyChecking no\" "
                 (if-let [port (:port options)] (format "-p %s" port)))
        cmd (format
             cmd ssh from (:username utils/*admin-user*)
             (compute/primary-ip (target/node)) to)]
    (utils/sh-script cmd)))

(resource/deflocal rsync
  rsync* [from to options])

(defn rsync-directory
  "Rsync from a local directory to a remote directory."
  [from to & options]
  (let [options (apply hash-map options)]
    (package/package "rsync")
    (apply directory/directory to
           (apply concat (select-keys options [:owner :group :mode])))
    (rsync from to options)))
