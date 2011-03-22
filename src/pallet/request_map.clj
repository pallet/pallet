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
  (-> request :server :node))

(defn phase
  "Current phase"
  [request]
  (:phase request))

(defn target-name
  "Name of the target-node."
  [request]
  (compute/hostname (target-node request)))

(defn target-id
  "Id of the target-node (unique for provider)."
  [request]
  (-> request :server :node-id))

(defn target-ip
  "IP of the target-node."
  [request]
  (compute/primary-ip (target-node request)))

(defn os-family
  "OS-Family of the target-node."
  [request]
  (-> request :server :image :os-family))

(defn os-version
  "OS-Family of the target-node."
  [request]
  (-> request :server :image :os-version))

(defn group-name
  "Group name of the target-node."
  [request]
  (-> request :server :group-name))

(defn tag
  "Tag of the target-node."
  {:deprecated "0.4.6"}
  [request]
  (group-name request))

(defn safe-name
  "Safe name for target machine.
   Some providers don't allow for node names, only node ids, and there is
   no guarantee on the id format."
  [request]
  (format "%s%s" (name (tag request)) (safe-id (name (target-id request)))))

(defn nodes-in-group
  "All nodes in the same tag as the target-node, or with the specified tag."
  ([request] (nodes-in-group request (group-name request)))
  ([request group-name]
     (filter
      #(= (name group-name) (compute/group-name %))
      (:all-nodes request))))

(defn nodes-in-tag
  "All nodes in the same tag as the target-node, or with the specified tag."
  {:deprecated "0.4.6"}
  ([request] (nodes-in-group request (group-name request)))
  ([request group-name] (nodes-in-group request group-name)))

(defn packager
  [request]
  (get-in request [:server :packager]))

(defn admin-group
  [request]
  (get-in request [:server :packager]))

(defn admin-user
  "User that remote commands are run under"
  [request]
  (-> request :server :node-id))
