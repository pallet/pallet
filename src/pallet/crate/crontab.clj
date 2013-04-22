(ns pallet.crate.crontab
  "crontab management"
  (:require
   [clojure.tools.logging :as logging]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore :refer [with-source-line-comments]])
  (:use
   [pallet.actions
    :only [content-options exec-checked-script file remote-file]]
   [pallet.api :only [server-spec plan-fn]]
   [pallet.crate
    :only [defplan assoc-settings get-settings update-settings]]
   [pallet.utils :only [apply-map]]))

(def system-cron-dir "/etc/cron.d")

(defplan settings
  "Define the crontab settings.  The settings are a map from user name to a map
   of keyword argument values for remote-file content (under :user) and a map
   from system facility name to a map of keyword argument values for remote-file
   content"
  [settings]
  (assoc-settings :crontab settings))

(defplan empty-settings
  "Define empty crontab settings. This can be used to ensure that settings are
   available for crontab, independently of whether any are specified elsewhere."
  []
  (update-settings :crontab identity))

(defplan user-settings
  "Define the user's crontab settings.  The settings are a map of keyword
  argument values for remote-file content."
  [user settings-map]
  (update-settings :crontab assoc-in [:user user] settings-map))

(defplan system-settings
  "Define the system's crontab settings.  The settings are a map of keyword
  argument values for remote-file content."
  [name settings-map]
  (update-settings :crontab assoc-in [:system name] settings-map))

(defn- in-file [user]
  "Create a path for a crontab.in file for the given user"
  (str (with-source-line-comments false
         (stevedore/script (~lib/user-home ~user)))
       "/crontab.in"))

(defplan create-user-crontab
  "Create user crontab for the given user."
  [user]
  (let [in-file (in-file user)
        settings (get-settings :crontab)
        content-spec (get (:user settings) user)]
    (apply-map
     remote-file
     in-file :owner user :mode "0600"
     (select-keys content-spec content-options))
    (exec-checked-script
     "Load crontab"
     ("crontab" -u ~user ~in-file))))

(defplan remove-user-crontab
  "Remove user crontab for the specified user"
  [user]
  (let [in-file (in-file user)]
    (file in-file :action :delete)
    (exec-checked-script
     "Remove crontab"
     ("crontab" -u ~user -r))))

(defplan user-crontabs
  "Write all user crontab files."
  [& {:keys [action] :or {action :create}}]
  (let [settings (get-settings :crontab nil)]
    (doseq [k (keys (:user settings))]
      (case action
        :create (create-user-crontab k)
        :remove (remove-user-crontab k)))))

(defn- system-cron-file
  "Path to system cron file for `name`"
  [name]
  (str system-cron-dir "/" name))

(defplan create-system-crontab
  "Create system crontab for the given name."
  [system]
  (let [path (system-cron-file system)
        settings (get-settings :crontab)]
    (apply-map
     remote-file
     path :owner "root" :group "root" :mode "0644"
     (select-keys
      (get (:system settings) system) content-options))))

(defplan remove-system-crontab
  "Remove system crontab for the given name"
  [system]
  (let [path (system-cron-file system)
        settings (get-settings :crontab)]
    (file path :action :delete)))

(defplan system-crontabs
  "Write all system crontab files."
  [& {:keys [action] :or {action :create}}]
  (let [settings (get-settings :crontab)]
    (doseq [k (keys (:system settings))]
     (case action
       :create (create-system-crontab k)
       :remove (remove-system-crontab k)))))

(def with-crontab
  (server-spec
   :phases {:settings (plan-fn
                        (empty-settings))
            :configure (plan-fn
                         (system-crontabs :action :create)
                         (user-crontabs :action :create))}))
