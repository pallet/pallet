(ns pallet.crate.automated-admin-user
  (:require
   [pallet.actions :refer [directory package-manager user]]
   [pallet.actions-impl :as actions-impl]
   [pallet.api :refer [plan-fn server-spec]]
   [pallet.core.session :refer [session]]
   [pallet.crate :refer [admin-user defplan]]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.sudoers :as sudoers]
   [pallet.script.lib :refer [file user-default-group]]
   [pallet.stevedore :refer [fragment]]))

(defplan authorize-user-key
  "Authorise a single key, specified as a path or as a byte array."
  [username path-or-bytes]
  (if (string? path-or-bytes)
    (ssh-key/authorize-key username (slurp path-or-bytes))
    (ssh-key/authorize-key username (String. ^bytes path-or-bytes))))

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
     (let [state-root (#'actions-impl/pallet-state-root (session))]
       (directory
        (fragment
         (file ~state-root "home" ~username))
        :owner username
        :group (if-let [group (:state-group (admin-user))]
                 group
                 (fragment @(user-default-group ~username)))))
     (doseq [kp public-key-paths]
       (authorize-user-key username kp))
     (sudoers/sudoers
      {}
      {:default {:env_keep "SSH_AUTH_SOCK"}}
      {username {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})))

(def with-automated-admin-user
  (server-spec
   :phases {:bootstrap (plan-fn
                          (package-manager :update)
                          (automated-admin-user))}))
