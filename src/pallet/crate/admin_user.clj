(ns pallet.crate.admin-user
  (:require
   [taoensso.timbre :refer [debugf]]
   [com.palletops.api-builder.core :refer [assert*]]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions :refer [directory exec-script* package-manager user]]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.sudoers :as sudoers]
   [pallet.plan :refer [defplan plan-fn]]
   [pallet.session :as session]
   [pallet.settings :refer [assoc-settings get-settings update-settings]]
   [pallet.script.lib :refer [user-home]]
   [pallet.spec :as spec]
   [pallet.stevedore :refer [fragment]]
   [pallet.utils :refer [conj-distinct]]
   [schema.core :as schema :refer [either eq optional-key validate]]))

(def facility ::admin-user)

(defn default-settings
  []
  {:install-sudo true
   :sudoers-instance-id nil})

(defn settings
  [session settings]
  (assoc-settings session
                  facility
                  (merge (default-settings) (dissoc settings :instance-id))
                  (select-keys settings [:instance-id])))

(defplan authorize-user-key
  "Authorise a single key, specified as a path or as a byte array."
  [session username path-or-bytes]
  (if (string? path-or-bytes)
    (ssh-key/authorize-key session username (slurp path-or-bytes))
    (ssh-key/authorize-key session username (String. ^bytes path-or-bytes))))

(defn default-sudoers-args
  [username]
  [{}
   {:default {:env_keep "SSH_AUTH_SOCK"}}
   {username {:ALL {:run-as-user :ALL :tags :NOPASSWD}}}
   {}])

(defn create-user-and-home
  [session username create-user create-home user-options]
  (cond
   (= ::unspecified create-user)
   (let [r (with-action-options session {:error-on-non-zero-exit false
                                         :action-id ::getent}
             (exec-script* session (fragment ("getent" passwd ~username))))]
     ;; if create-user not forced, only run if no existing user,
     ;; so we can run in the presence of pam_ldap users.
     (assert* r "Action return should be a map %s" r)
     (assert* (:exit r) "Action return should be a map with an :exit key: %s" r)
     (if (pos? (:exit r))
       (user session username (merge
                               {:create-home true :shell :bash}
                               user-options)))
     ;; If the user exists and the home directory does not then
     ;; create it. This is to allow for pam_mkhomedir, and is a hack,
     ;; as it doesn't copy /etc/skel
     (when create-home
       (directory session (fragment (user-home ~username)) {:owner username})))

   create-user (user session username
                     (merge
                      {:create-home true :shell :bash
                       ;; coreos defaults to !, which prevents login
                       :password "*"}
                      user-options))

   :else (when create-home
           (directory session (fragment (user-home ~username))
                      {:owner username}))))

;; (defplan create-admin
;;   "Builds a user for use in remote-admin automation. The user is given
;; permission to sudo without password, so that passwords don't have to appear
;; in scripts, etc.

;; `:username`
;; : the username to create.  Defaults to the current admin user.

;; `:public-key-paths`
;; : a sequence of paths to public keys to be authorised on the user

;; `:sudo`
;; : a flag to add the user to sudoers.  Defaults to true.

;; `:install-sudo`
;; : a flag to install the sudoers package.  Defaults to true.

;; `:create-user`
;; : a flag to create the user.  Defaults to true if the user doesn't
;; exist.

;; `:create-home`
;; : a flag to create the user's home directory.  Defauts to true.  When
;; users are managed by, e.g.  LDAP, you may need to set this to false.

;; `:user-options`
;; : a map of options to pass to the `user` action when creating the
;; user.
;; "
;;   [session {:keys [username public-key-paths public-keys sudo create-user
;;                    create-home install-sudo user-options]
;;             :or {sudo true
;;                  install-sudo true
;;                  create-home ::unspecified
;;                  create-user ::unspecified}}]
;;   (let [admin (session/user session)
;;         username (or username (:username admin))
;;         public-key-paths (or public-key-paths
;;                              (remove nil? [(:public-key-path admin)]))
;;         public-keys (or public-keys (remove nil? [(:public-key admin)]))]
;;     (when (and sudo install-sudo)
;;       (sudoers/install))
;;     (create-user-and-home session username create-user create-home user-options)
;;     (doseq [kp public-key-paths]
;;       (authorize-user-key session username kp))
;;     (doseq [k public-keys]
;;       (authorize-user-key session username k))
;;     (when sudo
;;       (sudoers/sudoers
;;        session
;;        {}
;;        {:default {:env_keep "SSH_AUTH_SOCK"}}
;;        {username {:ALL {:run-as-user :ALL :tags :NOPASSWD}}}))))


(defplan configure
  "Creates users, and Writes the configuration file for sudoers."
  [session {:keys [instance-id sudoers-instance-id] :as options}]
  (let [{:keys [install-sudo sudoers-instance-id users]}
        (get-settings session facility options)]
    (when install-sudo
      (sudoers/install session {:instance-id sudoers-instance-id}))
    (doseq [{:keys [username public-key-paths public-keys create-user
                    create-home user-options] :as user} users]
      (debugf "admin-user user %s" user)
      (create-user-and-home
       session username create-user create-home user-options)
      (doseq [kp public-key-paths]
        (authorize-user-key session username kp))
      (doseq [k public-keys]
        (authorize-user-key session username k)))
    (sudoers/configure session {:instance-id sudoers-instance-id})))


(def UserSettings
  {:username String
   :create-home (either schema/Bool (eq ::unspecified))
   :create-user (either schema/Bool (eq ::unspecified))
   (optional-key :public-key-paths) [String]
   (optional-key :public-keys) [(schema/either String bytes)]
   (optional-key :user-options) {schema/Keyword schema/Any}
   (optional-key :sudoers-args) schema/Any})

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
  ([session] (admin-user session {}))
  ([session {:keys [username public-key-paths public-keys sudo create-user
                    create-home user-options]
             :or {sudo true}
             :as options}]
     (let [user (session/user session)
           defaults {:create-home ::unspecified
                     :create-user ::unspecified
                     :username (:username user)
                     :public-key-paths (if (not public-keys)
                                           [(:public-key-path user)])}
           user-settings (merge defaults (dissoc options :sudo))]
       (validate UserSettings user-settings)
       (update-settings
        session facility {} update-in [:users] conj-distinct user-settings)
       (when sudo
         (apply
          sudoers/sudoers
          session
          (or (:sudoers-args user-settings)
              (default-sudoers-args (:username user-settings))))))))

(defn server-spec
  "Convenience server spec to add the current admin-user on bootstrap."
  [settings]
  (spec/server-spec
   {:phases {:settings (plan-fn [session]
                         (sudoers/settings session (:sudoers settings))
                         (pallet.crate.admin-user/settings
                          session (:automated-admin-user settings))
                         (admin-user session (:admin-user settings)))
             :bootstrap (plan-fn [session]
                          (configure session
                                     (select-keys settings [:instance-id])))}}))
