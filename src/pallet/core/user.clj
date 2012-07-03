(ns pallet.core.user
  "Admin user")

(defn default-private-key-path
  "Return the default private key path."
  []
  (str (System/getProperty "user.home") "/.ssh/id_rsa"))

(defn default-public-key-path
  "Return the default public key path"
  []
  (str (System/getProperty "user.home") "/.ssh/id_rsa.pub"))

(defrecord User
  [username public-key-path private-key-path passphrase
   password sudo-password no-sudo sudo-user])

(defn user? [user]
  (instance? pallet.core.user.User user))

(defn make-user
  "Creates a User record with the given username and options. Generally used
   in conjunction with *admin-user* and pallet.api/with-admin-user, or passed
   to `lift` or `converge` as the named :user argument.

   Options:
    - :public-key-path
    - :private-key-path
    - :passphrase
    - :password
    - :sudo-password (defaults to :password)
    - :no-sudo"
  [username {:keys [public-key-path private-key-path passphrase
                    password sudo-password no-sudo sudo-user]}]
  (User.
   username public-key-path private-key-path passphrase password
   sudo-password no-sudo sudo-user))

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
