(ns pallet.resource-apply
  (:require [clojure.contrib.str-utils2 :as string])
  (:use [pallet.target :only [with-target-template with-target-tag]]
        [pallet.utils :only [*admin-user*]]
        [pallet.compute :only [execute-script node-tag]]
        [pallet.core :only [*node-templates*]]
        [pallet.resource]
        [org.jclouds.compute :only [nodes]]
        [clojure.contrib.def :only [name-with-attributes]]))


(defn configure-node
  "Configure nodes using the specified configuration function.
This can be used to configure any machine that is reachable over ssh.

node - a jclouds node, a hostname or an ip address string
f - a resource fn."
  ([node f] (configure-node node f (*node-templates* (keyword (node-tag node)))))
  ([node f template] (configure-node node f template *admin-user*))
  ([node f template user & options]
     (let [tag-name (node-tag node)
           tag (keyword tag-name)]
       (apply execute-script (f tag template) node user options))))

(defn configure-nodes [nodes f user]
  (doseq [node nodes]
    (configure-node node f)))

(defmacro configure-resources
  "Generates a function that may me be used to run chef on a sequence of nodes"
  [[& user] & body]
  `(let [user# (or (first ~user) *admin-user*)]
     (fn [compute# nodes#]
       (configure-nodes
         nodes#
         (resource-fn ~@body)
         user#))))


