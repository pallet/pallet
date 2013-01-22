(ns pallet.core.session
  "Functions for querying sessions."
  (:require
   [pallet.compute :as compute]
   [pallet.node :as node]
   [pallet.utils :as utils])
  (:use
   [pallet.context :only [with-context]]
   [pallet.core.thread-local
    :only [with-thread-locals thread-local thread-local!]]))

;; Using the session var directly is to be avoided. It is a dynamic var in
;; order to provide thread specific bindings. The value is expected to be an
;; atom to allow in-place update semantics.
(def ^{:internal true :dynamic true :doc "Current session state"}
  *session*)

;;; # Session map low-level API
;;; The aim here is to provide an API that could possibly be backed by something
;;; other than a plain map.

(defn session
  "Return the current session, which implements clojure's map interfaces."
  [] (thread-local *session*))

(defmacro with-session
  [session & body]
  `(with-thread-locals [*session* ~session]
     ~@body))

(defn session!
  [session]
  (thread-local! *session* session))

;;; ## Session Context
;;; The session context is used in pallet core code.
(defmacro session-context
  "Defines a session context."
  {:indent 2}
  [pipeline-name event & args]
  (let [line (-> &form meta :line)]
    `(with-context
        ~(merge {:kw (list 'quote pipeline-name)
                 :msg (name pipeline-name)
                 :ns (list 'quote (ns-name *ns*))
                 :line line
                 :log-level :debug}
                event)
        ~@args)))


;;; # Session accessors

(defn safe-id
  "Computes a configuration and filesystem safe identifier corresponding to a
  potentially unsafe ID"
  [#^String unsafe-id]
  (utils/base64-md5 unsafe-id))

;; (defn phase
;;   "Current phase"
;;   [session]
;;   (:phase session))

(defn target
  "Target server."
  [session]
  (-> session :server))

(defn target-node
  "Target compute service node."
  [session]
  (-> session :server :node))

(defn target-name
  "Name of the target-node."
  [session]
  (node/hostname (target-node session)))

(defn target-id
  "Id of the target-node (unique for provider)."
  [session]
  (node/id (target-node session)))

(defn target-ip
  "IP of the target-node."
  [session]
  (node/primary-ip (target-node session)))

(comment
(defn target-roles
  "Roles of the target server."
  [session]
  [(-> session :server :roles) session])

(defn base-distribution
  "Base distribution of the target-node."
  [session]
  (compute/base-distribution (-> session :server :image)))
)

(defn os-family
  "OS-Family of the target-node."
  [session]
  (node/os-family (target-node session)))

(defn os-version
  "OS-Family of the target-node."
  [session]
  (node/os-version (target-node session)))

(defn group-name
  "Group name of the target-node."
  [session]
  (-> session :group :group-name))

(comment
   (defn safe-name
     "Safe name for target machine.
   Some providers don't allow for node names, only node ids, and there is
   no guarantee on the id format."
     [session]
     [(format
       "%s%s"
       (name (group-name session)) (safe-id (name (target-id session))))
      session])
   )

(defn targets
  "Targets for current converge."
  [session]
  (:service-state session))

(defn target-nodes
  "Target nodes for current converge."
  [session]
  (map :node (:service-state session)))

(defn nodes-in-group
  "All nodes in the same tag as the target-node, or with the specified
  group-name."
  [session group-name]
  (->>
   (:service-state session)
   (filter
    #(or (= (:group-name %) group-name)
         (when-let [group-names (:group-names %)] (group-names group-name))))
   (map :node)))

(comment
  (defn groups-with-role
    "All target groups with the specified role."
    [session role]
    (->>
     (keys (-> session :service-state :group->nodes))
     (filter #(when-let [roles (:roles %)] (when (roles role) %)))
     (map :group-name))))

(defn nodes-with-role
  "All target nodes with the specified role."
  [session role]
  (filter
   (fn [node]
     (when-let [roles (:roles node)]
       (roles role)))
   (:service-state session)))

(defn role->nodes-map
  "Returns a map from role to nodes."
  [session]
  (reduce
   (fn [m node]
     (reduce (fn [m role] (update-in m [role] conj node)) m (:roles node)))
   {}
   (:service-state session)))

(defn packager
  [session]
  (or
   (:packager (get-in session [:server :node]))
   (node/packager (get-in session [:server :node]))))

(defn admin-user
  "User that remote commands are run under"
  [session]
  {:pre [session (:user session)]}
  (:user session))

(defn admin-group
  "User that remote commands are run under"
  [session]
  (compute/admin-group
   (node/os-family (target-node session))
   (node/os-version (target-node session))))

(defn is-64bit?
  "Predicate for a 64 bit target"
  [session]
  (node/is-64bit? (target-node session)))

(comment
  (defn print-errors
    "Display errors from the session results."
    [session]
    (doseq [[target phase-results] (:results session)
            [phase results] phase-results
            result (filter
                    #(or (:error %) (and (:exit %) (not= 0 (:exit %))))
                    results)]
      (println target phase (:err result))))
  )
