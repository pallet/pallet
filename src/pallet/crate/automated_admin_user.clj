(ns pallet.crate.automated-admin-user
  (:require
   [pallet.actions :refer [directory package-manager plan-when-not user]]
   [pallet.api :refer [plan-fn] :as api]
   [pallet.crate :refer [admin-user defplan]]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.sudoers :as sudoers]
   [pallet.script.lib :refer [user-home]]
   [pallet.stevedore :refer [fragment]]
   [pallet.utils :refer [apply-map]]))

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
     ;; if create-user not forced, only run if no existing user,
     ;; so we can run in the presence of pam_ldap users.
     (plan-when-not (fragment ("getent" passwd ~username))
       (apply-map user username (merge
                                 {:create-home true :shell :bash}
                                 user-options)))
     ;; If the user exists and the home directory does not then
     ;; create it. This is to allow for pam_mkhomedir, and is a hack,
     ;; as it doesn't copy /etc/skel
     (when create-home
       (directory (fragment (user-home ~username)) :owner username)))

   create-user (user username (merge
                               {:create-home true :shell :bash}
                               user-options))

   :else (when create-home
           (directory (fragment (user-home ~username)) :owner username))))

(defplan create-admin
  "Builds a user for use in remote-admin automation. The user is given
permission to sudo without password, so that passwords don't have to appear
in scripts, etc.

`:username`
: the username to create.  Defaults to the current admin user.

`:public-key-paths`
: a sequence of paths to public keys to be authorised on the user

`:sudo`
: a flag to add the user to sudoers.  Defaults to true.

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
  [& {:keys [username public-key-paths sudo create-user create-home
             install-sudo user-options]
      :or {sudo true
           install-sudo true
           create-home ::unspecified
           create-user ::unspecified}}]
  (let [admin (admin-user)
        username (or username (:username admin))
        public-key-paths (or public-key-paths [(:public-key-path admin)])]
    (when (and sudo install-sudo)
      (sudoers/install))
    (create-user-and-home username create-user create-home user-options)
    (doseq [kp public-key-paths]
      (authorize-user-key username kp))
    (when sudo
      (sudoers/sudoers
       {}
       {:default {:env_keep "SSH_AUTH_SOCK"}}
       {username {:ALL {:run-as-user :ALL :tags :NOPASSWD}}}))))

(defn server-spec
  [{:keys [username public-key-paths sudo create-user create-home]
    :as options}]
  (api/server-spec
   :phases {:bootstrap (plan-fn
                        (package-manager :update)
                        (apply-map create-admin options))}))

(defplan automated-admin-user
  "Builds a user for use in remote-admin automation. The user is given
  permission to sudo without password, so that passwords don't have to appear
  in scripts, etc."
  ([]
     (let [user (admin-user)]
       (clojure.tools.logging/debugf "a-a-u for %s" user)
       (automated-admin-user
        (:username user)
        (:public-key-path user))))
  ([username]
     (let [user (admin-user)]
       (automated-admin-user username (:public-key-path user))))
  ([username & public-key-paths]
     (sudoers/install)
     (user username :create-home true :shell :bash)
     (doseq [kp public-key-paths]
       (authorize-user-key username kp))
     (sudoers/sudoers
      {}
      {:default {:env_keep "SSH_AUTH_SOCK"}}
      {username {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})))

(def with-automated-admin-user
  (api/server-spec
   :phases {:bootstrap (plan-fn
                          (package-manager :update)
                          (automated-admin-user))}))
