(ns pallet.user
  "User for authentication."
  (:require
   [clj-schema.schema
    :refer [constraints
            def-map-schema
            map-schema
            optional-path
            seq-schema
            wild]]
   [clojure.core.typed :refer [ann ann-record Nilable]]
   [pallet.contracts :refer [bytes? check-spec]]
   [pallet.core.types :refer [User]]
   [pallet.utils :refer [maybe-update-in obfuscate]]))

(def-map-schema user-schema
  (constraints
   (fn [{:keys [password private-key-path private-key]}]
     (or password private-key private-key-path)))
  [[:username] String
   (optional-path [:password]) [:or String nil]
   (optional-path [:sudo-password]) [:or String nil]
   (optional-path [:no-sudo]) wild
   (optional-path [:sudo-user]) [:or String nil]
   (optional-path [:temp-key]) wild
   (optional-path [:private-key-path]) [:or String nil]
   (optional-path [:public-key-path]) [:or String nil]
   (optional-path [:private-key]) [:or String bytes? nil]
   (optional-path [:public-key]) [:or String bytes? nil]
   (optional-path [:passphrase]) [:or String bytes? nil]])

(defmacro check-user
  [m]
  (check-spec m `user-schema &form))

(ann default-private-key-path [-> String])
(defn default-private-key-path
  "Return the default private key path."
  []
  (str (System/getProperty "user.home") "/.ssh/id_rsa"))

(ann default-public-key-path [-> String])
(defn default-public-key-path
  "Return the default public key path"
  []
  (str (System/getProperty "user.home") "/.ssh/id_rsa.pub"))

(defn user? [user]
  (check-user user))

;; TODO remove :no-check when core-type makes fields optional in map->
(ann ^:no-check make-user
     [String (HMap :optional
                   {:public-key-path String
                    :private-key-path String
                    :public-key String
                    :private-key String
                    :passphrase String
                    :password String
                    :sudo-password String
                    :no-sudo boolean
                    :sudo-user String}
                   :complete? true) -> User])
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
: flag to not use sudo (e.g. when the user has root privileges)."
  [username {:keys [public-key-path private-key-path
                    public-key private-key
                    passphrase
                    password sudo-password no-sudo sudo-user]
             :as options}]
  {:post [(check-user %)]}
  (assoc options :username username))

(ann *admin-user* User)
(def
  ^{:doc "The admin user is used for running remote admin commands that require
   root permissions.  The default admin user is taken from the
   pallet.admin.username property.  If not specified then the user.name property
   is used. The admin user can also be specified in config.clj when running
   tasks from the command line."
    :dynamic true}
  *admin-user*
  (make-user
   (let [username (or (. System getProperty "pallet.admin.username")
                      (. System getProperty "user.name"))]
     (assert username)
     username)
   {:private-key-path (default-private-key-path)
    :public-key-path (default-public-key-path)}))

(ann obfuscated-passwords [User -> User])
(defn obfuscated-passwords
  "Return a user with obfuscated passwords"
  [user]
  (-> user
      (maybe-update-in [:password] obfuscate)
      (maybe-update-in [:sudo-password] obfuscate)))
