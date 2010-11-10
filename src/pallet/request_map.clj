(ns pallet.request-map
  "Functions for querying and manipulating requests"
  (:require
   [pallet.compute :as compute])
  (:use
   [clojure.contrib.core :only [-?>]])
  (:import
   (java.security
    NoSuchAlgorithmException
    MessageDigest)
   (org.apache.commons.codec.binary Base64)))

(defn safe-id
  "Computes a configuration and filesystem safe identifier corresponding to a
  potentially unsafe ID"
  [#^String unsafe-id]
  (let [alg (doto (MessageDigest/getInstance "MD5")
              (.reset)
              (.update (.getBytes unsafe-id)))]
    (try
      (Base64/encodeBase64URLSafeString (.digest alg))
      (catch NoSuchAlgorithmException e
        (throw (new RuntimeException e))))))

(defn target-name
  "Name of the target-node."
  [request]
  (compute/hostname (:target-node request)))

(defn target-id
  "Id of the target-node (unique for provider)."
  [request]
  (compute/id (:target-node request)))

(defn target-ip
  "IP of the target-node."
  [request]
  (compute/primary-ip (:target-node request)))

(defn os-family
  "OS-Family of the target-node."
  [request]
  (-> request :node-type :image :os-family))

(defn tag
  "Tag of the target-node."
  [request]
  (-> request :node-type :tag))

(defn safe-name
  "Safe name for target machine.
   Some providers don't allow for node names, only node ids, and there is
   no guarantee on the id format."
  [request]
  (format "%s%s" (name (tag request)) (safe-id (target-id request))))

(defn nodes-in-tag
  "All nodes in the same tag as the target-node, or with the specified tag."
  ([request] (nodes-in-tag request (compute/tag (:target-node request))))
  ([request tag]
     (filter #(= (name tag) (compute/tag %)) (:target-nodes request))))

(defn packager
  [request]
  (compute/packager (-?> request :node-type :image)))

(defn script-template-keys
  "Find the script template keys for the request"
  [request]
  (let [node (:target-node request)]
    (distinct
     (filter
      identity
      [(-?> (.. node operatingSystem family) keyword str)
       (-?> (.. node operatingSystem version) keyword str)
       (-?> (.. node operatingSystem description) keyword str)]))))
