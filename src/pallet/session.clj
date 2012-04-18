(ns pallet.session
  "Functions for querying sessions.

   This is the official crate API for extracting information from the session."
  (:require
   [pallet.compute :as compute]
   [pallet.node :as node]
   [pallet.utils :as utils])
  (:use
   [pallet.monad :only [let-s]]
   [clojure.algo.monads :only [m-map]]))

(defn safe-id
  "Computes a configuration and filesystem safe identifier corresponding to a
  potentially unsafe ID"
  [#^String unsafe-id]
  (utils/base64-md5 unsafe-id))

(defn phase
  "Current phase"
  [session]
  [(:phase session) session])

(defn target-node
  "Target compute service node."
  [session]
  [(-> session :server :node) session])

(defn target-name
  "Name of the target-node."
  [session]
  [(node/hostname (target-node session)) session])

(defn target-id
  "Id of the target-node (unique for provider)."
  [session]
  [(-> session :server :node-id) session])

(defn target-ip
  "IP of the target-node."
  [session]
  [(node/primary-ip (target-node session)) session])

(defn target-roles
  "Roles of the target server."
  [session]
  [(-> session :server :roles) session])

(defn base-distribution
  "Base distribution of the target-node."
  [session]
  (compute/base-distribution (-> session :server :image)))

(defn os-family*
  "OS-Family of the target-node."
  [session]
  (-> session :server :image :os-family))

(defn os-family
  "OS-Family of the target-node."
  [session]
  [(os-family* session) session])

(defn os-version*
  "OS-Family of the target-node."
  [session]
  (-> session :server :image :os-version))

(defn os-version
  "OS-Family of the target-node."
  [session]
  [(os-version* session) session])

(defn group-name
  "Group name of the target-node."
  [session]
  [(-> session :server :group-name) session])

(defn safe-name
  "Safe name for target machine.
   Some providers don't allow for node names, only node ids, and there is
   no guarantee on the id format."
  [session]
  [(format
     "%s%s"
     (name (group-name session)) (safe-id (name (target-id session))))
   session])

(defn nodes-in-group
  "All nodes in the same tag as the target-node, or with the specified tag."
  ([]
     (let-s
       [group-name group-name
        all-nodes (get :all-nodes)]
       (filter #(= (name group-name) (node/group-name %)) all-nodes)))
  ([group-name]
     (let-s
       [all-nodes (get :all-nodes)]
       (filter #(= (name group-name) (node/group-name %)) all-nodes))))

(defn node-group-index
  "Yields the node's index within its group"
  [session]
  (let [indexes (->> (nodes-in-group session)
                  (map-indexed #(hash-map %2 %1))
                  (reduce merge))]
    (get indexes (target-node session))))

(defn groups-with-role
  "All target groups with the specified role."
  [role]
  (fn [session]
    [(->>
      (:node-set session)
      (filter #(when-let [roles (:roles %)] (roles role)))
      (map :group-name))
     session]))

(defn nodes-with-role
  "All target nodes with the specified role."
  [role]
  (let-s
    [groups (groups-with-role role)
     nodes (m-map nodes-in-group groups)]
    (apply concat nodes)))

(defn packager
  [session]
  [(get-in session [:server :packager]) session])

(defn admin-user
  "User that remote commands are run under"
  [session]
  [(:user session) session])

(defn admin-group
  "User that remote commands are run under"
  [session]
  [(compute/admin-group (:server session)) session])

(defn is-64bit?
  "Predicate for a 64 bit target"
  [session]
  [(node/is-64bit? (-> session :server :node)) session])

(defn print-errors
  "Display errors from the session results."
  [session]
  (doseq [[target phase-results] (:results session)
          [phase results] phase-results
          result (filter
                  #(or (:error %) (and (:exit %) (not= 0 (:exit %))))
                  results)]
    (println target phase (:err result))))
