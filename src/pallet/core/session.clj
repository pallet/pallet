(ns pallet.core.session
  "Functions for querying sessions."
  (:require
   [clojure.core.typed
    :refer [ann fn> tc-ignore
            Atom1 Map Nilable NilableNonEmptySeq NonEmptySeqable Seqable]]
   [pallet.compute :as compute :refer [packager-for-os]]
   [pallet.context :refer [with-context]]
   [pallet.core.plan-state :refer [get-settings]]
   [pallet.core.protocols :refer [Node]]
   [pallet.core.thread-local
    :refer [thread-local thread-local! with-thread-locals]]
   [pallet.core.types
    :refer [GroupName GroupSpec Keyword ServiceState Session TargetMap User]]
   [pallet.node :as node]
   [pallet.utils :as utils]))

;; Using the session var directly is to be avoided. It is a dynamic var in
;; order to provide thread specific bindings. The value is expected to be an
;; atom to allow in-place update semantics.
;; TODO - remove :no-check
(ann ^:no-check *session* (Atom1 Session))
(def ^{:internal true :dynamic true :doc "Current session state"}
  *session*)

;;; # Session map low-level API
;;; The aim here is to provide an API that could possibly be backed by something
;;; other than a plain map.

;; TODO - remove :no-check
(ann ^:no-check session [-> Session])
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

(ann session! [Session -> Session])
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
(ann safe-id [String -> String])
(defn safe-id
  "Computes a configuration and filesystem safe identifier corresponding to a
  potentially unsafe ID"
  [^String unsafe-id]
  (utils/base64-md5 unsafe-id))

;; (defn phase
;;   "Current phase"
;;   [session]
;;   (:phase session))

(ann target [Session -> TargetMap])
(defn target
  "Target server."
  [session]
  (-> session :server))

(ann target-node [Session -> Node])
(defn target-node
  "Target compute service node."
  [session]
  (-> session :server :node))

(ann target-name [Session -> String])
(defn target-name
  "Name of the target-node."
  [session]
  (node/hostname (target-node session)))

(ann target-id [Session -> String])
(defn target-id
  "Id of the target-node (unique for provider)."
  [session]
  (node/id (target-node session)))

(ann target-ip [Session -> String])
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

(ann os-family [Session -> Keyword])
(defn os-family
  "OS-Family of the target-node."
  [session]
  (or (node/os-family (target-node session))
      (-> session :server :image :os-family)
      (-> (get-settings (:plan-state session) (target-id session) :pallet/os {})
          :os-family)))

(ann os-version [Session -> String])
(defn os-version
  "OS-Family of the target-node."
  [session]
  (or (node/os-version (target-node session))
      (-> session :server :image :os-version)
      (-> (get-settings (:plan-state session) (target-id session) :pallet/os {})
          :os-version)))

(ann group-name [Session -> GroupName])
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

(ann targets [Session -> ServiceState])
(defn targets
  "Targets for current converge."
  [session]
  (:service-state session))

(ann ^:no-check target-nodes [Session -> (NilableNonEmptySeq Node)])
(defn target-nodes
  "Target nodes for current converge."
  [session]
  ;; TODO - change back to keyword when core.type can invoke them
  (map (fn> [t :- TargetMap] (get t :node)) (get session :service-state)))

(ann ^:no-check nodes-in-group [Session GroupName -> ServiceState])
(defn nodes-in-group
  "All nodes in the same tag as the target-node, or with the specified
  group-name."
  [session group-name]
  (->>
   (:service-state session)
   (filter
    (fn> [t :- TargetMap]
         (or (= (:group-name t) group-name)
             (when-let [group-names (:group-names t)]
               (get group-names group-name)))))))

(ann ^:no-check groups-with-role [Session -> (Seqable GroupSpec)])
(defn groups-with-role
  "All target groups with the specified role."
  [session role]
  (->>
   ;; TODO - change back to keyword when core.type can invoke them
   (get session :service-state)
   ;; TODO - change back to keyword when core.type can invoke them
   (filter (fn> [t :- TargetMap] (get (get t :roles #{}) role)))
   (map (fn> [t :- TargetMap] (dissoc t :node)))
   distinct))

(ann ^:no-check nodes-with-role [Session -> ServiceState])
(defn nodes-with-role
  "All target nodes with the specified role."
  [session role]
  (filter
   (fn> [node :- TargetMap]
     (when-let [roles (:roles node)]
       (get roles role)))
   ;; TODO - change back to keyword when core.type can invoke them
   (get session :service-state)))

;; TODO remove :no-check
(ann ^:no-check role->nodes-map [Session -> (Map Keyword (Seqable Node))])
(defn role->nodes-map
  "Returns a map from role to nodes."
  [session]
  (reduce
   (fn> [m :- (Map Keyword (Seqable Node))
         node :- TargetMap]
        (reduce (fn> [m :- (Map Keyword (Seqable Node))
                      role :- Keyword]
                     (update-in m [role] conj node))
                m
                ;; TODO - change back to keyword when core.type can invoke them
                (get node :roles)))
   {}
   ;; TODO - change back to keyword when core.type can invoke them
   (get session :service-state)))

(ann packager [Session -> Keyword])
(defn packager
  [session]
  (or
   ;; TODO - change back to keyword when core.type can invoke them
   (get (get session :server) :packager)
   ;; TODO - change to get-in when core.typed understands get.in
   (node/packager (get (get session :server) :node))
   (packager-for-os (os-family session) (os-version session))))

(ann admin-user [Session -> User])
(defn admin-user
  "User that remote commands are run under."
  [session]
  {:pre [session (-> session :environment :user)]}
  ;; Note: this is not (:user session), which is set to the actuall user used
  ;; for authentication when executing scripts, and may be different, e.g. when
  ;; bootstrapping.
  ;; TODO - change to get-in when core.typed understands get.in
  (get (get session :environment) :user))

(ann admin-group [Session -> String])
(defn admin-group
  "User that remote commands are run under"
  [session]
  (compute/admin-group
   (node/os-family (target-node session))
   (node/os-version (target-node session))))

(ann is-64bit? [Session -> boolean])
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
