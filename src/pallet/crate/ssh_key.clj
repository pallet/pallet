(ns pallet.crate.ssh-key
  (:require pallet.compat)
  (:use
   [pallet.target :only [admin-group]]
   [pallet.stevedore :only [script]]
   [pallet.template]
   [pallet.utils :only [cmd-join]]
   [pallet.resource :only [defresource defcomponent]]
   [pallet.resource.user :only [user-home]]
   [pallet.resource.file :only [chmod chown]]
   [pallet.resource.remote-file :only [remote-file*]]
   [pallet.resource.directory :only [directory*]]
   [clojure.contrib.logging]))

(pallet.compat/require-contrib)

(deftemplate authorized-keys-template
  [user keys]
  {{:path (str (script (user-home ~user)) "/.ssh/authorized_keys")
    :mode "0644" :owner user}
   (if (vector? keys)
     (string/join "\n" (map string/rtrim keys))
     keys)})

(defn- produce-authorize-key [[user keys]]
  (str
   (directory* (str (script (user-home ~user)) "/.ssh") :owner user :mode "755")
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

(defn install-key*
  [user key-name private-key-string public-key-string]
  (let [ssh-dir (str (script (user-home ~user)) "/.ssh")]
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
