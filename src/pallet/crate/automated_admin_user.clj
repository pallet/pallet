(ns pallet.crate.automated-admin-user
  (:use [pallet.resource.user :only [user]]
        [pallet.crate.sudoers]
        [pallet.crate.ssh-key :only [authorize-key]]
        [pallet.utils :only [default-public-key-path]]
        pallet.thread-expr)
  (:require
   [pallet.resource :as resource]))

(defn automated-admin-user
  "Builds a user for use in remote-admin automation. The user is given
  permission to sudo without password, so that passwords don't have to appear
  in scripts, etc."
  ([request] (automated-admin-user
              request
              (. System getProperty "user.name")
              (default-public-key-path)))
  ([request username] (automated-admin-user
                       request username (default-public-key-path)))
  ([request username & public-key-paths]
     (->
      request
      (user username :create-home true :shell :bash)
      (for-> [path-or-bytes public-key-paths]
       (if-> (string? path-or-bytes)
         (authorize-key username (slurp path-or-bytes))
         (authorize-key username (String. path-or-bytes))))
      (sudoers
       {} {} {username {:ALL {:run-as-user :ALL :tags :NOPASSWD}}}))))
