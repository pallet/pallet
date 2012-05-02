(ns pallet.crate.automated-admin-user
  (:require
   [pallet.context :as context]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.utils :as utils]
   [pallet.session :as session]
   [pallet.thread-expr :as thread-expr]
   [pallet.crate.sudoers :as sudoers])
  (:use
   [pallet.actions :only [user]]
   [pallet.phase :only [def-plan-fn]]))

(def-plan-fn authorize-user-key
  [username path-or-bytes]
  (if (string? path-or-bytes)
    (ssh-key/authorize-key username (slurp path-or-bytes))
    (ssh-key/authorize-key username (String. path-or-bytes))))

(def-plan-fn automated-admin-user
  "Builds a user for use in remote-admin automation. The user is given
  permission to sudo without password, so that passwords don't have to appear
  in scripts, etc."
  ([]
     [admin-user session/admin-user
      admin-user (m-result (or admin-user utils/*admin-user*))]
       (automated-admin-user
        (:username admin-user)
        (:public-key-path admin-user)))
  ([username]
     [user session/admin-user
      user (m-result (or user utils/*admin-user*))]
       (automated-admin-user username (:public-key-path user)))
  ([username & public-key-paths]
     (sudoers/install)
     (user username :create-home true :shell :bash)
     (map (partial authorize-user-key username) public-key-paths)
     (sudoers/sudoers
      {} {} {username {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})))
