(ns pallet.crate.automated-admin-user
  (:require
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.sudoers :as sudoers])
  (:use
   [pallet.actions :only [user package-manager]]
   [pallet.api :only [server-spec plan-fn]]
   [pallet.crate :only [admin-user defplan]]))

(defplan authorize-user-key
  "Authorise a single key, specified as a path or as a byte array."
  [username path-or-bytes]
  (if (string? path-or-bytes)
    (ssh-key/authorize-key username (slurp path-or-bytes))
    (ssh-key/authorize-key username (String. path-or-bytes))))

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
      {} {} {username {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})))

(def with-automated-admin-user
  (server-spec
   :bootstrap (plan-fn
                (package-manager :update)
                (automated-admin-user))))
