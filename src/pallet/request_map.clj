(ns pallet.request-map
  "Functions for querying and manipulating requests"
  (:require
   [pallet.target :as target]
   [pallet.compute :as compute])
  (:use
   [clojure.contrib.core :only [-?>]]))

(defn target-name
  "Name of the target-node."
  [request]
  (.getName (:target-node request)))

(defn target-id
  "Id of the target-node (unique for provider)."
  [request]
  (.getId (:target-node request)))

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

(defn nodes-in-tag
  "All nodes in the same tag as the target-node, or with the specified tag."
  ([request] (nodes-in-tag request (.getTag (:target-node request))))
  ([request tag] (filter #(= (name tag) (.getTag %)) (:target-nodes request))))

(defn packager
  [request]
  (target/packager (-?> request :node-type :image)))

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
