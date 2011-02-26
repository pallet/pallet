(ns pallet.request-map
  "Functions for querying requests.

   This is the official crate API for extracting information from the request."
  (:require
   [pallet.compute :as compute]
   [pallet.utils :as utils])
  (:use
   [clojure.contrib.core :only [-?>]]))

(defn safe-id
  "Computes a configuration and filesystem safe identifier corresponding to a
  potentially unsafe ID"
  [#^String unsafe-id]
  (utils/base64-md5 unsafe-id))

(defn phase
  "Current phase"
  [request]
  (:phase request))

(defn target-node
  "Target compute service node."
  [request]
  (-> request :group-node :node))

(defn target-name
  "Name of the target-node."
  [request]
  (compute/hostname (target-node request)))

(defn target-id
  "Id of the target-node (unique for provider)."
  [request]
  (-> request :group-node :node-id))

(defn target-ip
  "IP of the target-node."
  [request]
  (compute/primary-ip (target-node request)))

(defn os-family
  "OS-Family of the target-node."
  [request]
  (-> request :group-node :image :os-family))

(defn os-version
  "OS-Family of the target-node."
  [request]
  (-> request :group-node :image :os-version))

(defn tag
  "Tag of the target-node."
  [request]
  (-> request :group-node :tag))

(defn safe-name
  "Safe name for target machine.
   Some providers don't allow for node names, only node ids, and there is
   no guarantee on the id format."
  [request]
  (format "%s%s" (name (tag request)) (safe-id (name (target-id request)))))

(defn nodes-in-tag
  "All nodes in the same tag as the target-node, or with the specified tag."
  ([request] (nodes-in-tag request (tag request)))
  ([request tag]
     (filter #(= (name tag) (compute/tag %)) (:all-nodes request))))

(defn packager
  [request]
  (get-in request [:group-node :packager]))

(defn admin-group
  [request]
  (get-in request [:group-node :packager]))
