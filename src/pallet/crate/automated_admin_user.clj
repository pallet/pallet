(ns pallet.crate.automated-admin-user
  (:use [pallet.resource.user :only [user]]
        [pallet.crate.sudoers]
        [pallet.crate.authorize-public-key]
        [pallet.utils :only default-public-key-path]))

(defn automated-admin-user
  "Builds a user for use in remote-admin automation.  The user is given
  permission to sudo without password, so that passwords don't have to appear
  in scripts, etc."
  ([username] (automated-admin-user username (default-public-key-path)))
  ([username public-key-path]
     (user username :create-home true)
     (sudoers {} {} {username {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})
     (authorize-public-key username (slurp public-key-path))))


