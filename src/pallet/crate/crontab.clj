(ns pallet.crate.crontab
  "crontab management"
  (:refer-clojure :exclude [alias])
  (:require
   pallet.compat
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [pallet.resource :as resource]
   [pallet.resource.file :as file]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.user :as user]))

(def system-cron-dir "/etc/cron.d")

(defn crontab*
  [user & options]
  (let [options (apply hash-map options)
        in-file (str (stevedore/script (user/user-home user)) "/crontab.in")]
    (condp = (get options :action :create)
      :create (utils/cmd-join
               [(apply
                 remote-file/remote-file* in-file :owner user :mode "0600"
                 (apply
                  concat
                  (select-keys options remote-file/content-options)))
                (stevedore/script ("crontab" -u ~user ~in-file))])
      :remove (utils/cmd-join
               [(file/file* in-file :action :delete)
                (stevedore/script ("crontab" -u ~user -r))]))))

(defn system-crontab*
  [name & options]
  (let [options (apply hash-map options)
        cron-file (str system-cron-dir "/" name)]
    (condp = (get options :action :create)
      :create (apply
               remote-file/remote-file* cron-file
               :owner "root" :group "root" :mode "0644"
               (apply
                concat
                (select-keys options remote-file/content-options)))
      :remove (file/file* cron-file :action :delete))))

(resource/defresource crontab
  "Manage a user's crontab file. Valid actions are :create or :remove.
Options are as for remote-file."
  crontab* [user & options])

(resource/defresource system-crontab
  "Manage a system crontab file. Valid actions are :create or :remove.
Options are as for remote-file."
  system-crontab* [name & options])
