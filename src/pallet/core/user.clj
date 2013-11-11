(ns pallet.core.user
  "User for authentication."
  (:require
   [clojure.core.typed :refer [ann ann-record Nilable]]
   [pallet.contracts :refer [check-user]]
   [pallet.core.types :refer [User]]
   [pallet.utils :refer [maybe-update-in obfuscate]]))

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

;; (ann-record User [username :- String
;;                   public-key-path :- (Nilable String)
;;                   private-key-path :- (Nilable String)
;;                   public-key :- (Nilable String)
;;                   private-key :- (Nilable String)
;;                   passphrase :- (Nilable String)
;;                   password :- (Nilable String)
;;                   sudo-password :- (Nilable String)
;;                   no-sudo :- (Nilable boolean)
;;                   sudo-user :- (Nilable String)])
;; (defrecord User
;;     [username public-key-path private-key-path public-key private-key
;;      passphrase password sudo-password no-sudo sudo-user])

(ann ^:no-check user? (predicate User))
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
