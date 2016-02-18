(ns pallet.crate.user
  "Configure users, authorised for SSH access."
  (:require
   [clojure.tools.logging :refer [debugf]]
   [pallet.action :refer [with-action-options]]
   [pallet.actions :as actions :refer [directory exec-script*]]
   [pallet.api :as api :refer [plan-fn]]
   [pallet.crate :as crate
    :refer [assoc-settings defplan get-settings update-settings]]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.script.lib :refer [user-home]]
   [pallet.stevedore :as stevedore :refer [fragment]]
   [pallet.utils :refer [apply-map deep-merge]]
   [schema.core :as schema :refer [either eq optional-key validate]]))

(def facility ::user)

(defn default-settings
  []
  {:users []})

(defn settings
  [settings]
  (assoc-settings facility
                  (merge (default-settings) (dissoc settings :instance-id))
                  (select-keys settings [:instance-id])))

(defplan authorize-user-key
  "Authorise a single key, specified as a path or as a byte array."
  [username path-or-bytes]
  (if (string? path-or-bytes)
    (ssh-key/authorize-key username (slurp path-or-bytes))
    (ssh-key/authorize-key username (String. ^bytes path-or-bytes))))

(defn create-user-and-home
  [username create-user create-home user-options]
  (cond
    (= ::unspecified create-user)
    (do
      (apply-map actions/user username (merge
                                        {:create-home true :shell :bash}
                                        user-options))
      ;; If the user exists and the home directory does not then
      ;; create it. This is to allow for pam_mkhomedir, and is a hack,
      ;; as it doesn't copy /etc/skel
      (when create-home
        (directory (fragment (user-home ~username)) :owner username)))

    create-user (actions/user username
                              (merge
                               {:create-home true :shell :bash
                                ;; coreos defaults to !, which prevents login
                                :password "*"}
                               user-options))

    :else (when create-home
            (directory (fragment (user-home ~username))
                       {:owner username}))))

(defplan configure
  "Creates users."
  [{:keys [instance-id] :as options}]
  (let [{:keys [users]} (get-settings facility options)]
    (debugf "user configure %s" users)
    (doseq [{:keys [username public-key-paths public-keys create-user
                    create-home user-options] :as user} users]
      (debugf "user user %s" user)
      (when-let [group (:group user-options)]
        (actions/group group))
      (create-user-and-home
       username create-user create-home user-options)
      (doseq [kp public-key-paths]
        (authorize-user-key username kp))
      (doseq [k public-keys]
        (authorize-user-key username k)))))

(def UserSettings
  {:username String
   :create-home (either schema/Bool (eq ::unspecified))
   :create-user (either schema/Bool (eq ::unspecified))
   (optional-key :public-key-paths) [String]
   (optional-key :public-keys) [(schema/either String bytes)]
   (optional-key :user-options) {schema/Keyword schema/Any}})

(defn user
  "Add a user

Builds a user.

`:username`
: the username to create.  Defaults to the current admin user.

`:public-key-paths`
: a sequence of paths to public keys to be authorised on the user

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
  ([] (user {}))
  ([{:keys [username public-key-paths public-keys create-user
            create-home user-options]
     :as options}]
   (let [user (crate/admin-user)
         defaults {:create-home ::unspecified
                   :create-user ::unspecified
                   :username (:username user)
                   :public-key-paths (if (not public-keys)
                                       [(:public-key-path user)])}
         user-settings (merge defaults options)]
     (validate UserSettings user-settings)
     (debugf "user %s" user)
     (update-settings
      facility {} update-in [:users]
      (fn [c s]
        (if-let [u (first (filter #(= (:username %) (:username s)) c))]
          (vec (conj (remove #(= u %) c) (deep-merge u s)))
          (vec (conj c s))))
      user-settings))))

(defn server-spec
  "Convenience server spec to add users."
  [settings]
  (api/server-spec
   :phases {:settings (plan-fn
                       (pallet.crate.user/settings settings))
            :bootstrap (plan-fn
                        (configure (select-keys settings [:instance-id])))
            :reconfigure (plan-fn
                          (configure (select-keys settings [:instance-id])))}))
