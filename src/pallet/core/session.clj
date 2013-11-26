(ns pallet.core.session
  "Functions for querying sessions."
  (:require
   [pallet.compute :as compute :refer [packager-for-os]]
   [pallet.context :refer [with-context]]
   [pallet.core.plan-state :refer [get-settings]]
   [pallet.core.thread-local
    :refer [thread-local thread-local! with-thread-locals]]
   [pallet.node :as node]
   [pallet.utils :as utils]))

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
  []
  (assert (bound? #'*session*)
          "Session not bound.  The session is only bound within a phase.")
  (thread-local *session*))

(defmacro ^{:requires [#'with-thread-locals]}
  with-session
  [session & body]
  `(with-thread-locals [*session* ~session]
     ~@body))

(defn session!
  [session]
  (thread-local! *session* session))

;;; ## Session Context
;;; The session context is used in pallet core code.
(defmacro ^{:requires [#'with-context]} session-context
  "Defines a session context."
  {:indent 2}
  [pipeline-name event & args]
  (let [line (-> &form meta :line)]
    `(with-context
        ~(merge {:kw (list 'quote pipeline-name)
                 :msg (name pipeline-name)
                 :ns (list 'quote (ns-name *ns*))
                 :line line
                 :log-level :trace}
                event)
        ~@args)))


;;; # Session accessors
(defn file-uploader
  [session]
  (::file-uploader session))


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
  (or (node/os-family (target-node session))
      (-> session :server :image :os-family)
      (-> (get-settings (:plan-state session) (target-id session) :pallet/os {})
          :os-family)))

(defn os-version
  "OS-Family of the target-node."
  [session]
  (or (node/os-version (target-node session))
      (-> session :server :image :os-version)
      (-> (get-settings (:plan-state session) (target-id session) :pallet/os {})
          :os-version)))

(defn group-name
  "Group name of the target-node."
  [session]
  (-> session :server :group-name))

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


(defn groups-with-role
  "All target groups with the specified role."
  [session role]
  (->>
   (:service-state session)
   (filter #((:roles %) role))
   (map #(dissoc % :node))
   distinct))

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
   (-> session :server :packager)
   (node/packager (get-in session [:server :node]))
   (packager-for-os (os-family session) (os-version session))))

(defn admin-user
  "User that remote commands are run under."
  [session]
  {:pre [session (-> session :environment :user)]}
  ;; Note: this is not (:user session), which is set to the actuall user used
  ;; for authentication when executing scripts, and may be different, e.g. when
  ;; bootstrapping.
  (-> session :environment :user))

(defn admin-group
  "User that remote commands are run under"
  [session]
  (compute/admin-group
   (node/os-family (target-node session))
   (node/os-version (target-node session))))

(defn effective-username
  "Return the effective username."
  [session]
  {:post [%]}
  (or
   (-> session :action :sudo-user)
   (-> session :environment :user :sudo-user)
   (-> session :environment :user :username)))

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
