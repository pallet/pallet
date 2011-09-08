(ns pallet.crate.crontab
  "crontab management"
  (:refer-clojure :exclude [alias])
  (:require
   [pallet.action.exec-script :as exec-script]
   [pallet.action.file :as file]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.user :as user]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils])
  (:use pallet.thread-expr))

(def system-cron-dir "/etc/cron.d")

(defn crontab
  "Manage a user's crontab file. Valid actions are :create or :remove.
   Options are as for remote-file."
  [session user & {:keys [action] :or {action :create} :as options}]
  (let [in-file (str (stevedore/script (~lib/user-home user)) "/crontab.in")]
    (case action
      :create (->
               session
               (apply->
                remote-file/remote-file
                in-file :owner user :mode "0600"
                (apply
                 concat
                 (select-keys options remote-file/content-options)))
               (exec-script/exec-checked-script
                "Load crontab"
                ("crontab" -u ~user ~in-file)))
      :remove (->
               session
               (file/file in-file :action :delete)
               (exec-script/exec-checked-script
                "Remove crontab"
                ("crontab" -u ~user -r))))))

(defn system-crontab
  "Manage a system crontab file. Valid actions are :create or :remove.
Options are as for remote-file."
  [session name & options]
  (let [options (apply hash-map options)
        cron-file (str system-cron-dir "/" name)]
    (condp = (get options :action :create)
        :create (apply
                 remote-file/remote-file session cron-file
                 :owner "root" :group "root" :mode "0644"
                 (apply
                  concat
                  (select-keys options remote-file/content-options)))
        :remove (file/file session cron-file :action :delete))))
