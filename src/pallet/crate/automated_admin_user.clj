(ns pallet.crate.automated-admin-user
  (:use [pallet.resource.user :only [user]]
        [pallet.crate.sudoers]
        [pallet.crate.ssh-key :only [authorize-key]]
        [pallet.utils :only [default-public-key-path]]))

(defn automated-admin-user
  "Builds a user for use in remote-admin automation. The user is given
  permission to sudo without password, so that passwords don't have to appear
  in scripts, etc."
  ([] (automated-admin-user
       (. System getProperty "user.name")
       (default-public-key-path)))
  ([username] (automated-admin-user
               username (default-public-key-path)))
  ([username & public-key-paths]
     (user username :create-home true :shell :bash)
     (doseq [path-or-bytes public-key-paths]
       (if (string? path-or-bytes)
         (authorize-key username (slurp path-or-bytes))
         (authorize-key username (String. path-or-bytes))))
     (sudoers {} {} {username {:ALL {:run-as-user :ALL :tags :NOPASSWD}}})))


