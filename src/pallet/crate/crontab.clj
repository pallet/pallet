(ns pallet.crate.crontab
  "crontab management"
  (:refer-clojure :exclude [alias])
  (:require
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [pallet.resource :as resource]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.file :as file]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.user :as user])
  (:use pallet.thread-expr))

(def system-cron-dir "/etc/cron.d")

(defn crontab
  "Manage a user's crontab file. Valid actions are :create or :remove.
   Options are as for remote-file."
  [request user & {:keys [action] :or {action :create} :as options}]
  (let [in-file (str (stevedore/script (user/user-home user)) "/crontab.in")]
    (case action
      :create (->
               request
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
               request
               (file/file in-file :action :delete)
               (exec-script/exec-checked-script
                "Remove crontab"
                ("crontab" -u ~user -r))))))

(defn system-crontab
  "Manage a system crontab file. Valid actions are :create or :remove.
Options are as for remote-file."
  [request name & options]
  (let [options (apply hash-map options)
        cron-file (str system-cron-dir "/" name)]
    (condp = (get options :action :create)
        :create (apply
                 remote-file/remote-file request cron-file
                 :owner "root" :group "root" :mode "0644"
                 (apply
                  concat
                  (select-keys options remote-file/content-options)))
        :remove (file/file request cron-file :action :delete))))
