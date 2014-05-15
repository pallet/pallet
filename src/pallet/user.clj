(ns pallet.user
  "User for authentication."
  (:require
   [clojure.java.io :refer [file]]
   [pallet.core.api-builder :refer [defn-api]]
   [pallet.utils :refer [first-existing-file maybe-update-in obfuscate]]
   [pallet.utils.schema] ;; for explain on bytes
   [schema.core :as schema :refer [check required-key optional-key validate]]))

(def UserArgMap
  {(optional-key :password) String
   (optional-key :sudo-password) String
   (optional-key :no-sudo) schema/Bool
   (optional-key :sudo-user) String
   (optional-key :temp-key) schema/Bool
   (optional-key :private-key-path) String
   (optional-key :public-key-path) String
   (optional-key :private-key) (schema/either String bytes)
   (optional-key :public-key) (schema/either String bytes)
   (optional-key :passphrase) (schema/either String bytes)})

(def UserUnconstrained
  (assoc UserArgMap :username String))

(defn has-credentials?
  "Check to see if some form of credential is specified."
  [{:keys [password private-key-path private-key]}]
  (or password private-key private-key-path))

(def User
  (schema/both
   (schema/pred has-credentials? 'has-credentials?)
   UserUnconstrained))

(defn user?
  "Predicate to test for a valid user map."
  [m]
  (not (check User m)))

(def key-files ["id_rsa" "id_dsa"])

(def ssh-home (file (System/getProperty "user.home") ".ssh"))

(defn default-private-key-path
  "Return the default private key path."
  []
  (if-let [f (first-existing-file ssh-home key-files)]
    (str f)))

(defn default-public-key-path
  "Return the default public key path"
  []
  (if-let [f (first-existing-file ssh-home (map #(str % ".pub") key-files))]
    (str f)))

;; TODO remove :no-check when core-type makes fields optional in map->
(defn-api make-user
  "Creates a User record with the given username and options. Generally used
   in conjunction with *admin-user* and with-admin-user, or passed
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
  {:sig [[String UserArgMap :- User]]}
  [username {:keys [public-key-path private-key-path
                    public-key private-key
                    passphrase
                    password sudo-password no-sudo sudo-user]
             :as options}]
  (assoc options :username username))

(defn-api default-user
  "The admin user is used for running remote admin commands that require
   root permissions.  The default admin user is taken from the
   pallet.admin.username property.  If not specified then the user.name property
   is used. The admin user can also be specified in config.clj when running
   tasks from the command line."
  {:sig [[:- User]]}
  []
  (make-user
   (let [username (or (. System getProperty "pallet.admin.username")
                      (. System getProperty "user.name"))]
     (assert username)
     username)
   {:private-key-path (default-private-key-path)
    :public-key-path (default-public-key-path)}))

(def
  ^{:doc "The admin user is used for running remote admin commands that require
   root permissions.  The default admin user is taken from the
   pallet.admin.username property.  If not specified then the user.name property
   is used. The admin user can also be specified in config.clj when running
   tasks from the command line."
    :dynamic true}
  *admin-user* (default-user))

(defmacro with-admin-user
  "Specify the admin user for running remote commands.  The user is
   specified either as user map (see the pallet.user/make-user
   convenience fn).

   This is mainly for use at the repl, since the admin user can be specified
   functionally using the :user key in a lift or converge call, or in the
   environment."
  [user & exprs]
  `(binding [*admin-user* ~user]
     ~@exprs))

(defn obfuscated-passwords
  "Return a user with obfuscated passwords"
  [user]
  (-> user
      (maybe-update-in [:password] obfuscate)
      (maybe-update-in [:sudo-password] obfuscate)))

(defn sudo-username
  "Return the sudo username for a user map.  Returns nil if :no-sudo
  is true."
  [{:keys [no-sudo sudo-user username] :as user}]
  (and (not no-sudo) (or sudo-user "root")))

(defn effective-username
  "Return the effective username for a user map."
  [{:keys [no-sudo sudo-user username] :as user}]
  (or (sudo-username user)
      username))
