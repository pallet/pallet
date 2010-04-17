(ns pallet.crate.ssh-key
  (:require
   pallet.compat
   [pallet.stevedore :as stevedore])
  (:use
   [pallet.target :only [admin-group]]
   [pallet.stevedore :only [script]]
   [pallet.template]
   [pallet.utils :only [cmd-join]]
   [pallet.resource :only [defresource defcomponent]]
   [pallet.resource.user :only [user-home]]
   [pallet.resource.file :only [chmod chown file*]]
   [pallet.resource.remote-file :only [remote-file*]]
   [pallet.resource.directory :only [directory*]]
   [clojure.contrib.logging]))

(pallet.compat/require-contrib)

(defn user-ssh-dir [user]
  (str (script (user-home ~user)) "/.ssh/"))

(deftemplate authorized-keys-template
  [user keys]
  {{:path (str (user-ssh-dir user) "authorized_keys")
    :mode "0644" :owner user}
   (if (vector? keys)
     (string/join "\n" (map string/rtrim keys))
     keys)})

(defn- produce-authorize-key [[user keys]]
  (str
   (directory* (user-ssh-dir user) :owner user :mode "755")
   (apply-templates authorized-keys-template [user keys])))

(def authorize-key-args (atom []))

(defn- conj-merge [a b]
  (if (vector? a)
    (conj a b)
    [a b]))

(defn- apply-authorize-keys [args]
  (string/join
   ""
   (map
    produce-authorize-key
    (reduce #(merge-with conj-merge %1 (apply array-map %2)) {} args))))

(defresource authorize-key
  "Authorize a public key on the specified user."
  authorize-key-args apply-authorize-keys [username public-key-string])

(defn authorize-key-for-localhost* [user public-key-filename & options]
  (let [options (apply hash-map options)
        target-user (get options :authorize-for-user user)]
    (cmd-join
     [(script
       (var key_file ~(str (user-ssh-dir user) public-key-filename))
       (var auth_file ~(str (user-ssh-dir target-user) "authorized_keys")))
      (file* (str (user-ssh-dir target-user) "authorized_keys")
             :owner target-user :mode "644")
      (script
       (if-not (grep @(cat @key_file) @auth_file)
         (cat @key_file ">>" @auth_file)))])))

(defcomponent authorize-key-for-localhost
  "Authorize a user's public key on the specified user, for ssh access to
  localhost.  The :authorize-for-user option can be used to specify the
  user to who's authorized_keys file is modified."
  authorize-key-for-localhost* [username public-key-filename & options])

(defn install-key*
  [user key-name private-key-string public-key-string]
  (let [ssh-dir (user-ssh-dir user)]
    (cmd-join
     [(directory* ssh-dir :owner user :mode "755")
      (remote-file*
       (str ssh-dir "/" key-name) :owner user :mode 600
       :content private-key-string)
      (remote-file*
       (str ssh-dir "/" key-name ".pub") :owner user :mode 644
       :content public-key-string)])))

(defcomponent install-key
  "Install a ssh private key"
  install-key* [username private-key-name private-key-string public-key-string])

(def ssh-default-filenames
     {"rsa1" "identity"
      "rsa" "id_rsa"
      "dsa" "id_dsa"})

(defn generate-key*
  [user & options]
  (let [options (apply hash-map options)
        key-type (get options :type "rsa")
        ssh-dir (user-ssh-dir user)
        path (get options :file
                  (script
                   (str ~(user-ssh-dir user)
                        ~(ssh-default-filenames key-type))))
        passphrase (get options :passphrase "\"\"")]
    (cmd-join
     [(directory* ssh-dir :owner user :mode "755")
      (script
       (var key_path ~path)
       (if-not (file-exists? @key_path)
         (ssh-keygen ~(stevedore/map-to-arg-string
                       {:f (script @key_path) :t key-type :N passphrase}))))
      (file* (script @key_path) :owner user :mode "0600")
      (file* (str (script @key_path) ".pub") :owner user :mode "0644")])))

(defcomponent generate-key
  "Generate an ssh key pair for the given user. Options are
     :file path     -- output file name
     :type key-type -- key type selection"
  generate-key* [username & options])
