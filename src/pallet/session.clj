(ns pallet.session
  "Functions for querying sessions.

   This is the official crate API for extracting information from the session."
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
  [session]
  (:phase session))

(defn target-node
  "Target compute service node."
  [session]
  (-> session :server :node))

(defn phase
  "Current phase"
  [session]
  (:phase session))

(defn target-name
  "Name of the target-node."
  [session]
  (compute/hostname (target-node session)))

(defn target-id
  "Id of the target-node (unique for provider)."
  [session]
  (-> session :server :node-id))

(defn target-ip
  "IP of the target-node."
  [session]
  (compute/primary-ip (target-node session)))

(defn base-distribution
  "Base distribution of the target-node."
  [session]
  (compute/base-distribution (-> session :server :image)))

(defn os-family
  "OS-Family of the target-node."
  [session]
  (-> session :server :image :os-family))

(defn os-version
  "OS-Family of the target-node."
  [session]
  (-> session :server :image :os-version))

(defn group-name
  "Group name of the target-node."
  [session]
  (-> session :server :group-name))

(defn safe-name
  "Safe name for target machine.
   Some providers don't allow for node names, only node ids, and there is
   no guarantee on the id format."
  [session]
  (format
   "%s%s"
   (name (group-name session)) (safe-id (name (target-id session)))))

(defn nodes-in-group
  "All nodes in the same tag as the target-node, or with the specified tag."
  ([session] (nodes-in-group session (group-name session)))
  ([session group-name]
     (filter
      #(= (name group-name) (compute/group-name %))
      (:all-nodes session))))

(defn groups-with-role
  "All target groups with the specified role."
  [session role]
  (->>
   (:node-set session)
   (filter #(when-let [roles (:roles %)] (roles role)))
   (map :group-name)))

(defn nodes-with-role
  "All target nodes with the specified role."
  [session role]
  (mapcat #(nodes-in-group session %) (groups-with-role session role)))

(defn packager
  [session]
  (get-in session [:server :packager]))

(defn admin-user
  "User that remote commands are run under"
  [session]
  (:user session))

(defn admin-group
  "User that remote commands are run under"
  [session]
  (compute/admin-group (:server session)))

(defn is-64bit?
  "Predicate for a 64 bit target"
  [session]
  (compute/is-64bit? (target-node session)))
