(ns pallet.crate.automated-admin-user
  (:require
   [pallet.action.user :as user]
   [pallet.context :as context]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.utils :as utils]
   [pallet.session :as session]
   [pallet.thread-expr :as thread-expr]
   [pallet.crate.sudoers :as sudoers]))

(defn automated-admin-user
  "Builds a user for use in remote-admin automation. The user is given
  permission to sudo without password, so that passwords don't have to appear
  in scripts, etc."
  ([session]
     (let [user (or (session/admin-user session) utils/*admin-user*)]
       (automated-admin-user session (:username user) (:public-key-path user))))
  ([session username]
     (let [user (or (session/admin-user session) utils/*admin-user*)]
       (automated-admin-user session username (:public-key-path user))))
  ([session username & public-key-paths]
     (context/with-phase-context
       :automated-admin-user ["Automated admin user %s" username]
       (->
        session
        (sudoers/install)
        (user/user username :create-home true :shell :bash)
        (thread-expr/for->
         [path-or-bytes public-key-paths]
         (thread-expr/if->
          (string? path-or-bytes)
          (ssh-key/authorize-key username (slurp path-or-bytes))
          (ssh-key/authorize-key username (String. path-or-bytes))))
        (sudoers/sudoers
         {} {} {username {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})))))
