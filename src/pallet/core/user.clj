(ns pallet.core.user
  "User for authentication."
  (:require
   [pallet.utils :refer [maybe-update-in obfuscate]]))

(defn default-private-key-path
  "Return the default private key path."
  []
  (str (System/getProperty "user.home") "/.ssh/id_rsa"))

(defn default-public-key-path
  "Return the default public key path"
  []
  (str (System/getProperty "user.home") "/.ssh/id_rsa.pub"))

(defrecord User
    [username public-key-path private-key-path public-key private-key
     passphrase password sudo-password no-sudo sudo-user state-root
     state-group])

(defn user? [user]
  (instance? pallet.core.user.User user))

(defn make-user
  "Creates a User record with the given username and options. Generally used
   in conjunction with *admin-user* and pallet.api/with-admin-user, or passed
   to `lift` or `converge` as the named :user argument.

   Options:

`:public-key-path`
: path string to public key file

`:private-key-path`
: path string to private key file

`:public-key`
: public key as a string or byte array

`:private-key`
: private key as a string or byte array

`:passphrase`
: passphrase for private key

`:password`
: ssh user password

`:sudo-password`
: password for sudo (defaults to :password)

`:sudo-user`
: the user to sudo to

`:no-sudo`
: flag to not use sudo (e.g. when the user has root privileges).

`:state-root`
: directory on target to use for pallet state files.  Defaults to
  /var/lib/pallet.

`:state-group`
: group shared between admin user and sudo-user.  Used when uploading
  files. Needed only if the sudo user is unprivileged, and the admin
  user can't chown/chgrp files.  "

  [username {:keys [public-key-path private-key-path
                    public-key private-key
                    passphrase
                    password sudo-password no-sudo sudo-user
                    state-root state-group]
             :as options}]
  (map->User (assoc options :username username)))

(def
  ^{:doc "The admin user is used for running remote admin commands that require
   root permissions.  The default admin user is taken from the
   pallet.admin.username property.  If not specified then the user.name property
   is used. The admin user can also be specified in config.clj when running
   tasks from the command line."
    :dynamic true}
  *admin-user*
  (make-user
   (or (. System getProperty "pallet.admin.username")
       (. System getProperty "user.name"))
   {:private-key-path (default-private-key-path)
     :public-key-path (default-public-key-path)}))

(defn obfuscated-passwords
  "Return a user with obfuscated passwords"
  [user]
  (-> user
      (maybe-update-in [:password] obfuscate)
      (maybe-update-in [:sudo-password] obfuscate)))
