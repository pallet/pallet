(ns pallet.crate.ssh-key
  (:require
   pallet.compat
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [pallet.resource.directory :as directory]
   [pallet.resource.remote-file :as remote-file])
  (:use
   [pallet.target :only [admin-group]]
   [pallet.template]
   [pallet.resource :only [defresource defaggregate]]
   [pallet.resource.user :only [user-home]]
   [pallet.resource.file :only [chmod chown file*]]
   [clojure.contrib.logging]))

(pallet.compat/require-contrib)

(defn user-ssh-dir [user]
  (str (stevedore/script (user-home ~user)) "/.ssh/"))

(deftemplate authorized-keys-template
  [user keys]
  {{:path (str (user-ssh-dir user) "authorized_keys")
    :mode "0644" :owner user}
   (if (vector? keys)
     (string/join "\n" (map string/rtrim keys))
     keys)})

(defn- authorize-key* [user keys]
  (utils/do-script
   (directory/directory* (user-ssh-dir user) :owner user :mode "755")
   (remote-file/remote-file*
    (str (user-ssh-dir user) "authorized_keys")
    :content (if (vector? keys)
               (string/join "\n" (map string/rtrim keys))
               keys)
    :owner user :mode "0644")))

(defn- conj-merge [a b]
  (if (vector? a)
    (conj a b)
    [a b]))

(defn- apply-authorize-keys [args]
  (string/join
   ""
   (map
    (partial apply authorize-key*)
    (reduce #(merge-with conj-merge %1 (apply array-map %2)) {} args))))

(defaggregate authorize-key
  "Authorize a public key on the specified user."
  apply-authorize-keys [username public-key-string])

(defn authorize-key-for-localhost* [user public-key-filename & options]
  (let [options (apply hash-map options)
        target-user (get options :authorize-for-user user)]
    (utils/do-script
     (stevedore/script
      (var key_file ~(str (user-ssh-dir user) public-key-filename))
      (var auth_file ~(str (user-ssh-dir target-user) "authorized_keys")))
     (directory/directory* (user-ssh-dir target-user) :owner target-user :mode "755")
     (file* (str (user-ssh-dir target-user) "authorized_keys")
            :owner target-user :mode "644")
     (stevedore/checked-script "authorize-key"
      (if-not (grep @(cat @key_file) @auth_file)
        (cat @key_file ">>" @auth_file))))))

(defresource authorize-key-for-localhost
  "Authorize a user's public key on the specified user, for ssh access to
  localhost.  The :authorize-for-user option can be used to specify the
  user to who's authorized_keys file is modified."
  authorize-key-for-localhost* [username public-key-filename & options])

(defn install-key*
  [user key-name private-key-string public-key-string]
  (let [ssh-dir (user-ssh-dir user)]
    (utils/do-script
     (directory/directory* ssh-dir :owner user :mode "755")
     (remote-file/remote-file*
      (str ssh-dir key-name) :owner user :mode "600"
      :content private-key-string)
     (remote-file/remote-file*
      (str ssh-dir key-name ".pub") :owner user :mode "644"
      :content public-key-string))))

(defresource install-key
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
                  (stevedore/script
                   (str ~(user-ssh-dir user)
                        ~(ssh-default-filenames key-type))))
        passphrase (get options :passphrase "\"\"")]
    (utils/do-script
     (directory/directory* ssh-dir :owner user :mode "755")
     (stevedore/checked-script "ssh-keygen"
      (var key_path ~path)
      (if-not (file-exists? @key_path)
        (ssh-keygen ~(stevedore/map-to-arg-string
                      {:f (stevedore/script @key_path) :t key-type :N passphrase}))))
     (file* (stevedore/script @key_path) :owner user :mode "0600")
     (file* (str (stevedore/script @key_path) ".pub") :owner user :mode "0644"))))

(defresource generate-key
  "Generate an ssh key pair for the given user. Options are
     :file path     -- output file name
     :type key-type -- key type selection"
  generate-key* [username & options])
