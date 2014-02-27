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
   [clojure.core.typed :refer [ann ann-record pred Nilable NonEmptySeq]]
   [clojure.java.io :refer [file]]
   [pallet.contracts :refer [bytes? check-spec]]
   [pallet.core.types :refer [Bytes User]]
   [pallet.utils :refer [first-existing-file maybe-update-in obfuscate]]))

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

(ann key-files '[(Value "id_rsa") (Value "id_dsa")])
(def key-files ["id_rsa" "id_dsa"])

(ann ssh-home java.io.File)
(def ssh-home (file (System/getProperty "user.home") ".ssh"))

(ann default-private-key-path [-> (U nil String)])
(defn default-private-key-path
  "Return the default private key path."
  []
  (if-let [f (first-existing-file ssh-home key-files)]
    (str f)))

(ann default-public-key-path [-> (U nil String)])
(defn default-public-key-path
  "Return the default public key path"
  []
  (if-let [f (first-existing-file ssh-home (map #(str % ".pub") key-files))]
    (str f)))

(ann user? (predicate User))
(defn user? [user]
  (check-user user))

;; TODO remove :no-check when core-type makes fields optional in map->
(ann ^:no-check make-user
     [String (HMap :optional
                   {:public-key-path String
                    :private-key-path String
                    :public-key (U String Bytes)
                    :private-key (U String Bytes)
                    :passphrase String
                    :password String
                    :sudo-password String
                    :no-sudo boolean
                    :sudo-user String}
                   :complete? true) -> User])
(defn make-user
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

(ann obfuscated-passwords [User -> User])
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
