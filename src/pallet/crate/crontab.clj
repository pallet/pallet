(ns pallet.crate.crontab
  "crontab management"
  (:require
   [pallet.actions :refer [content-options exec-checked-script file remote-file]]
   [pallet.plan :refer [defplan plan-fn]]
   [pallet.script.lib :as lib]
   [pallet.settings :refer [assoc-settings get-settings update-settings]]
   [pallet.spec :as spec]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore :refer [with-source-line-comments]]
   [pallet.utils :refer [apply-map]]))

(def system-cron-dir "/etc/cron.d")

(defplan settings
  "Define the crontab settings.  The settings are a map from user name to a map
   of keyword argument values for remote-file content (under :user) and a map
   from system facility name to a map of keyword argument values for remote-file
   content"
  [session settings]
  (assoc-settings session :crontab settings))

(defplan empty-settings
  "Define empty crontab settings. This can be used to ensure that settings are
   available for crontab, independently of whether any are specified elsewhere."
  [session]
  (update-settings session :crontab identity))

(defplan user-settings
  "Define the user's crontab settings.  The settings are a map of keyword
  argument values for remote-file content."
  [session user settings-map]
  (update-settings session :crontab assoc-in [:user user] settings-map))

(defplan system-settings
  "Define the system's crontab settings.  The settings are a map of keyword
  argument values for remote-file content."
  [session name settings-map]
  (update-settings session :crontab assoc-in [:system name] settings-map))

(defn- in-file [user]
  "Create a path for a crontab.in file for the given user"
  (str (with-source-line-comments false
         (stevedore/script (~lib/user-home ~user)))
       "/crontab.in"))

(defplan create-user-crontab
  "Create user crontab for the given user."
  [session user]
  (let [in-file (in-file user)
        settings (get-settings session :crontab)
        content-spec (get (:user settings) user)]
    (remote-file
     session in-file
     (merge
      {:owner user :mode "0600"}
      (select-keys content-spec content-options)))
    (exec-checked-script
     session
     "Load crontab"
     ("crontab" -u ~user ~in-file))))

(defplan remove-user-crontab
  "Remove user crontab for the specified user"
  [session user]
  (let [in-file (in-file user)]
    (file session in-file {:action :delete})
    (exec-checked-script
     session
     "Remove crontab"
     ("crontab" -u ~user -r))))

(defplan user-crontabs
  "Write all user crontab files."
  [session & {:keys [action] :or {action :create}}]
  (let [settings (get-settings session :crontab nil)]
    (doseq [k (keys (:user settings))]
      (case action
        :create (create-user-crontab session k)
        :remove (remove-user-crontab session k)))))

(defn- system-cron-file
  "Path to system cron file for `name`"
  [name]
  (str system-cron-dir "/" name))

(defplan create-system-crontab
  "Create system crontab for the given name."
  [session system]
  (let [path (system-cron-file system)
        settings (get-settings session :crontab)]
    (remote-file
     session
     path
     (merge
      {:owner "root" :group "root" :mode "0644"}
      (select-keys
       (get (:system settings) system) content-options)))))

(defplan remove-system-crontab
  "Remove system crontab for the given name"
  [session system]
  (let [path (system-cron-file system)
        settings (get-settings session :crontab)]
    (file session path :action :delete)))

(defplan system-crontabs
  "Write all system crontab files."
  [session & {:keys [action] :or {action :create}}]
  (let [settings (get-settings session :crontab)]
    (doseq [k (keys (:system settings))]
     (case action
       :create (create-system-crontab session k)
       :remove (remove-system-crontab session k)))))

(defn server-spec [settings]
  (spec/server-spec
   {:phases {:settings (plan-fn [session]
                         (pallet.crate.crontab/settings session settings))
             :configure (plan-fn [session]
                          (system-crontabs session :action :create)
                          (user-crontabs session :action :create))}}))
