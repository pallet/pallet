(ns pallet.crate.crontab
  "crontab management"
  (:require
   [clojure.tools.logging :as logging]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore])
  (:use
   [pallet.actions :only [exec-checked-script file remote-file content-options]]
   [pallet.api :only [server-spec plan-fn]]
   [pallet.crate
    :only [def-plan-fn assoc-settings get-settings update-settings]]
   [pallet.utils :only [apply-map]]))

(def system-cron-dir "/etc/cron.d")

(def-plan-fn settings
  "Define the crontab settings.  The settings are a map from user name to a map
   of keyword argument values for remote-file content (under :user) and a map
   from system facility name to a map of keyword argument values for remote-file
   content"
  [settings]
  (assoc-settings :crontab settings))

(def-plan-fn empty-settings
  "Define empty crontab settings. This can be used to ensure that settings are
   available for crontab, independently of whether any are specified elsewhere."
  []
  (update-settings :crontab identity))

(def-plan-fn user-settings
  "Define the user's crontab settings.  The settings are a map of keyword
  argument values for remote-file content."
  [user settings-map]
  (update-settings :crontab assoc-in [:user user] settings-map))

(def-plan-fn system-settings
  "Define the system's crontab settings.  The settings are a map of keyword
  argument values for remote-file content."
  [name settings-map]
  (update-settings :crontab assoc-in [:system name] settings-map))

(defn- in-file [user]
  "Create a path for a crontab.in file for the given user"
  (str (stevedore/script (~lib/user-home ~user)) "/crontab.in"))

(def-plan-fn create-user-crontab
  "Create user crontab for the given user."
  [user]
  [in-file (m-result (in-file user))
   settings (get-settings :crontab)
   content-spec (m-result (get (:user settings) user))]
  (apply-map
   remote-file
   in-file :owner user :mode "0600"
   (select-keys content-spec content-options))
  (exec-checked-script
   "Load crontab"
   ("crontab" -u ~user ~in-file)))

(def-plan-fn remove-user-crontab
  "Remove user crontab for the specified user"
  [user]
  [in-file (m-result (in-file user))]
  (file in-file :action :delete)
  (exec-checked-script
   "Remove crontab"
   ("crontab" -u ~user -r)))

(def-plan-fn user-crontabs
  "Write all user crontab files."
  [& {:keys [action] :or {action :create}}]
  [settings (get-settings :crontab nil)]
  (map
   (case action
     :create create-user-crontab
     :remove remove-user-crontab)
   (keys (:user settings))))

(defn- system-cron-file
  "Path to system cron file for `name`"
  [name]
  (str system-cron-dir "/" name))

(def-plan-fn create-system-crontab
  "Create system crontab for the given name."
  [system]
  [path (m-result (system-cron-file system))
   settings (get-settings :crontab)]
  (apply-map
   remote-file
   path :owner "root" :group "root" :mode "0644"
   (select-keys
    (get (:system settings) system) content-options)))

(def-plan-fn remove-system-crontab
  "Remove system crontab for the given name"
  [system]
  [path (m-result (system-cron-file system))
   settings (get-settings :crontab)]
  (file path :action :delete))

(def-plan-fn system-crontabs
  "Write all system crontab files."
  [& {:keys [action] :or {action :create}}]
  [settings (get-settings :crontab)]
  (map
   (case action
     :create create-system-crontab
     :remove remove-system-crontab)
   (keys (:system settings))))

(def with-crontab
  (server-spec
   :settings (empty-settings)
   :configure (plan-fn
                (system-crontabs :action :create)
                (user-crontabs :action :create))))
