(ns pallet.crate.admin-user
  "Configure users with sudo permissions."
  (:require
   [clojure.tools.logging :refer [debugf]]
   [pallet.api :as api :refer [plan-fn]]
   [pallet.crate :as crate :refer [assoc-settings defplan get-settings]]
   [pallet.crate.sudoers :as sudoers]
   [pallet.crate.user :as user]
   [pallet.utils :refer [apply-map]]
   [schema.core :as schema :refer [either eq optional-key validate]]))

(def facility ::admin-user)

(defn default-settings
  []
  {:install-sudo true
   :sudoers-instance-id nil})

(defn settings
  [settings]
  (assoc-settings facility
                  (merge (default-settings) (dissoc settings :instance-id))
                  (select-keys settings [:instance-id])))

(defn default-sudoers-args
  [username]
  [{}
   {:default {:env_keep "SSH_AUTH_SOCK"}}
   {username {:ALL {:run-as-user :ALL :tags :NOPASSWD}}}])

(defplan configure
  "Writes the configuration file for sudoers."
  [{:keys [instance-id sudoers-instance-id] :as options}]
  (let [{:keys [install-sudo sudoers-instance-id]}
        (get-settings facility options)]
    (debugf "admin-user configure")
    (when install-sudo
      (apply-map sudoers/install {:instance-id sudoers-instance-id}))
    (sudoers/configure {:instance-id sudoers-instance-id})))


(def UserSettings
  (assoc user/UserSettings
         (optional-key :sudoers-args) schema/Any))

(defn default-user []
  (let [user (crate/admin-user)
        user (if-let [k (:public-key user)]
               (assoc :public-keys [k])
               user)
        user (if-let [p (:public-key-path user)]
               (assoc :public-key-paths [p])
               user)]
    (dissoc user
            :state-root
            :sudo-password
            :private-key-path
            :password
            :passphrase
            :no-sudo
            :state-group
            :public-key-path
            :sudo-user
            :private-key
            :public-key)))

(defn admin-user
  "Add an admin user

Builds a user for use in remote-admin automation. The user is given
permission to sudo without password, so that passwords don't have to appear
in scripts, etc.

`:username`
: the username to create.  Defaults to the current admin user.

`:public-key-paths`
: a sequence of paths to public keys to be authorised on the user

`:sudo`
: a flag to add the user to sudoers.  Defaults to true.

`:sudoers-args`
: a vector of args for passing to sudoers. Defaults to password-less
sudo access to everything.

`:install-sudo`
: a flag to install the sudoers package.  Defaults to true.

`:create-user`
: a flag to create the user.  Defaults to true if the user doesn't
exist.

`:create-home`
: a flag to create the user's home directory.  Defauts to true.  When
users are managed by, e.g.  LDAP, you may need to set this to false.

`:user-options`
: a map of options to pass to the `user` action when creating the
user.
"
  ([] (admin-user (default-user)))
  ([{:keys [username public-key-paths public-keys sudo create-user
            create-home user-options]
     :or {sudo true}
     :as options}]
   (debugf "admin-user %s" options)
   (let [user-settings (dissoc options :sudo :sudoers-args)]
     (user/user user-settings)
     (when sudo
       (let [args (vec (or (:sudoers-args user-settings)
                           (default-sudoers-args (:username user-settings))))]
         (debugf "admin-user sudoers %s" args)
         (apply sudoers/sudoer (conj args {})))))))

(defn server-spec
  "Convenience server spec to add the current admin-user on bootstrap."
  [settings]
  (api/server-spec
   :extends [(user/server-spec (user/default-settings))]
   :phases {:settings (plan-fn
                       (sudoers/settings (:sudoers settings))
                       (pallet.crate.admin-user/settings
                           (:automated-admin-user settings))
                       (admin-user))
            :bootstrap (plan-fn
                        (configure (select-keys settings [:instance-id])))
            :reconfigure (plan-fn
                          (configure (select-keys settings [:instance-id])))}))
