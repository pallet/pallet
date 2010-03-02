(ns pallet.crate.authorize-key
  (:require [clojure.contrib.str-utils2 :as string])
  (:use
   [pallet.target :only [admin-group]]
   [pallet.stevedore :only [script]]
   [pallet.template]
   [pallet.resource :only [defresource]]
   [pallet.resource.user :only [user-home]]
   [clojure.contrib.logging]))


(deftemplate authorized-keys-template
  [user keys]
  {{:path (str (script (user-home ~user)) "/.ssh/authorized_keys")
    :mode "0644" :owner user}
   (if (vector? keys)
     (string/join \newline keys)
     keys)})


(def authorize-key-args (atom []))

(defn- conj-merge [a b]
  (if (vector? a)
    (conj a b)
    [a b]))

(defn- apply-authorize-keys [args]
  (string/join
   ""
   (map
    #(apply-templates authorized-keys-template %)
    (reduce #(merge-with conj-merge %1 (apply array-map %2)) {} args))))

(defresource authorize-key
  "Authorize a public key on the specified user."
  authorize-key-args apply-authorize-keys [username public-key-string])

