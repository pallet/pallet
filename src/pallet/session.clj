(ns pallet.session
  "Functions for querying sessions.

The session is a map with well defined keys:

`:execution-state`
: a map of values used by the pallet implementation to record values
  while executing.  The values are not held in mutable state.  The
  values are:

- `:executor`
  : an function to execute actions.

- `:recorder`
  : an implementation of the Record and Results protocols.

- `:action-options`
  : the current action options

- `:user`
  : the default admin user

- `:compute`
  : the default compute service

- `:environment`
  : the effective environment

- `:extension`
  : an extension map for abstractions build on pallet core, and
  requiring execution state.  Un-namespaced keywords as keys are
  reserved for pallet's own abstractions.

`:plan-state`
: an implementation of the StateGet and StateUpdate protocols.  The
  data in the plan-state is mutable in plan functions.

`:target`
: the current target
"
  (:require
   [clojure.core.typed
    :refer [ann def-alias fn> inst tc-ignore
            Atom1 Map Nilable NilableNonEmptySeq NonEmptySeqable Seqable]]
   [pallet.core.types ;; before anything that uses the protocols this annotates
    :refer [assert-type-predicate
            ActionOptions BaseSession Executor ExecuteStatusFn Keyword PlanState
            Recorder ScopeMap Session User]]
   [pallet.core.executor :refer [executor?]]
   [pallet.core.plan-state
    :refer [get-settings get-scopes merge-scopes plan-state?]]
   [pallet.core.plan-state.protocols :refer [StateGet]]
   [pallet.core.recorder :refer [recorder?]]
   [pallet.core.recorder.protocols :refer [Record]]
   [pallet.core.recorder.null :refer [null-recorder]]
   [pallet.node :as node]
   [pallet.user :refer [user?]]
   [pallet.utils :as utils]
   ;; [circle.schema-typer :refer [def-schema-alias def-validator]]
   [schema.core :as schema :refer [check required-key optional-key validate]]))

;; (defmethod circle.schema-typer/convert :default [s]
;;   (if (instance? Class s)
;;     (symbol (.getName s))
;;     (assert nil (str "Don't know how to convert " (pr-str s)))))

;; (defmethod circle.schema-typer/convert nil [s]
;;   nil)

;; (defmethod circle.schema-typer/convert clojure.lang.PersistentList [s]
;;   (assert nil (str "Don't know how to convert " s))
;;   (symbol (.getName s)))

(ann execution-state (HMap))
(def execution-state
  {:executor pallet.core.executor.protocols.ActionExecutor
   (optional-key :recorder) pallet.core.recorder.protocols.Record
   (optional-key :action-options) {schema/Keyword schema/Any}
   (optional-key :user) schema/Any
   (optional-key :environment) schema/Any
   (optional-key :extension) {schema/Keyword schema/Any}})

;; (def-schema-alias ExecutionState execution-state)
;; (def-validator validate-execution-state ExecutionState execution-state)

(ann ^:no-check base-session (HMap))
(def base-session
  {:execution-state execution-state
   (optional-key :plan-state) pallet.core.plan_state.protocols.StateGet
   :type (schema/eq ::session)})

(def target-session
  (assoc base-session :target {schema/Keyword schema/Any}))


(ann ^:no-check base-session? (predicate BaseSession))
(defn base-session?
  [x]
  (not (schema/check base-session x)))


;; (ann ^:no-check target-session? (predicate BaseSession))
(defn target-session?
  [x]
  (not (schema/check target-session x)))

(defn validate-target-session
  [x]
  (schema/validate target-session x))


;; (def-schema-alias BaseSession base-session)
;; (def-validator validate-base-session BaseSession base-session)

(ann create [(HMap
              :mandatory {:executor Executor}
              :optional {:recorder Record
                         :plan-state StateGet})
             -> BaseSession])
(defn create
  "Create a session with the specified components."
  [{:keys [recorder plan-state executor action-options user
           environment]
    :as args}]
  {:pre [(or (nil? plan-state) (plan-state? plan-state))
         (executor? executor)
         (or (= ::empty (:action-options args ::empty))
             (map? action-options))]
   :post [(validate base-session %)]}
  (merge
   {:type ::session
    :execution-state (select-keys
                      args
                      [:environment :executor :recorder :action-options :user])}
   (if plan-state
     {:plan-state plan-state})))


;; ;; Using the session var directly is to be avoided. It is a dynamic var in
;; ;; order to provide thread specific bindings. The value is expected to be an
;; ;; atom to allow in-place update semantics.
;; ;; TODO - remove :no-check
;; (ann ^:no-check *session* (Atom1 Session))
;; (def ^{:internal true :dynamic true :doc "Current session state"}
;;   *session*)

;; ;;; # Session map low-level API
;; ;;; The aim here is to provide an API that could possibly be backed by something
;; ;;; other than a plain map.

;; ;; TODO - remove :no-check
;; (ann ^:no-check session [-> Session])
;; (defn session
;;   "Return the current session, which implements clojure's map interfaces."
;;   []
;;   (assert (bound? #'*session*)
;;           "Session not bound.  The session is only bound within a phase.")
;;   ;; (thread-local *session*)
;;   @*session*
;;   )

;; (defmacro ^{:requires [#'with-thread-locals]}
;;   with-session
;;   [session & body]
;;   `(binding [*session* (atom ~session)]
;;      ~@body))

;; (ann session! [Session -> Session])
;; (defn session!
;;   [session]
;;   (reset! *session* session))

;; ;;; ## Session modifiers
;; (defn assoc-session!
;;   "Assoc key value pairs in the session."
;;   [& kvs]
;;   (apply swap! *session* assoc kvs))

;; (defn update-in-session!
;;   "Assoc key value pairs in the session."
;;   [ks f args]
;;   (clojure.tools.logging/debugf "update-in-session! %s %s %s" ks f args)
;;   (apply swap! *session* update-in ks f args))

;; (defn plan-state!
;;   "Set the plan state"
;;   [m]
;;   {:pre [(satisfies? pallet.core.plan-state.protocols/StateGet m)]}
;;   (assoc-session! :plan-state m))

(ann plan-state [BaseSession -> PlanState])
(defn plan-state
  "Get the plan state"
  [session]
  (:plan-state session))

;; (defn recorder!
;;   "Set the action recorder"
;;   [f]
;;   (assoc-session! :recorder f))

(ann recorder [BaseSession -> Recorder])
(defn recorder
  "Get the action recorder"
  [session]
  (-> session :execution-state :recorder))

;; (defmacro with-recorder
;;   "Set the recorder for the scope of the body."
;;   [recorder & body]
;;   `(let [r# (recorder)]
;;      (try
;;        (recorder! ~recorder)
;;        ~@body
;;        (finally (recorder! r#)))))

(ann executor [BaseSession -> Executor])
(defn executor
  "Get the action executor."
  [session]
  {:pre [(or (base-session? session) (target-session? session))]
   :post [(executor? %)]}
  (-> session :execution-state :executor))

;; TODO this is an inconsistent set- function, as it isn't mutating.
;; Consider ! decorating the other setters?
(ann set-executor [BaseSession Executor -> BaseSession])
(defn set-executor
  "Get the action executor."
  [session executor]
  {:pre [(executor? executor)]}
  (assoc-in session [:execution-state :executor] executor))

;; (ann execute-status-fn [BaseSession -> ExecuteStatusFn])
;; (defn execute-status-fn
;;   "Get the action execute status function"
;;   [session]
;;   {:post [(fn? %)]}
;;   (-> session :execution-state :execute-status-fn))

;; (defn executor!
;;   "Set the action executor"
;;   [f]
;;   (assoc-session! :executor f))

;; (defn execute-status-fn!
;;   "Set the action execute status function"
;;   [f]
;;   (assoc-session! :execute-status-fn f))

(ann action-options [BaseSession -> HMap])
(defn action-options
  "Get the action options."
  [session]
  (-> session :execution-state :action-options))

(ann environment [BaseSession -> HMap])
(defn ^:internal environment
  "Get the environment map."
  [session]
  (-> session :execution-state :environment))

(ann set-environment [HMap -> BaseSession])
(defn ^:internal set-environment
  "Set the environment map."
  [session environment]
  (assoc-in session [:execution-state :environment] environment))

(defn ^:internal update-environment
  "Update the environment map."
  [session f args]
  (apply update-in session [:execution-state :environment] f args))


(defn ^:internal extension
  "Get the extension data for the specified keyword."
  [session extension-kw]
  (get-in session [:execution-state :extension extension-kw]))

(defn ^:internal update-extension
  "Update the extension."
  [session extension-kw f args]
  (apply update-in session [:execution-state :extension extension-kw] f args))

(defn ^:internal set-extension
  "Update the extension."
  [session extension-kw value]
  (assoc-in session [:execution-state :extension extension-kw] value))


(def-alias SessionModifier
  (TFn [[t :variance :contravariant]]
       (All [[x :< BaseSession]] (Fn [x t -> x]))))

(ann ^:no-check set-user [BaseSession User -> BaseSession])
(defn user
  "Return a session with `user` as the known admin user."
  [session]
  {:post [(user? %)]}
  (-> session :execution-state :user))

(ann ^:no-check set-user [BaseSession User -> BaseSession])
(defn set-user
  "Return a session with `user` as the known admin user."
  [session user]
  {:pre [(user? user)]}
  (assoc-in session [:execution-state :user] user))

(ann ^:no-check set-recorder [BaseSession Recorder -> BaseSession])
(defn set-recorder
  "Return a session with `recorder` as the action result recorder."
  [session recorder]
  {:post [(recorder? (pallet.session/recorder %))]}
  (assoc-in session [:execution-state :recorder] recorder))

(ann set-target [BaseSession TargetMap -> Session])
(defn set-target
  "Return a session with `:target` as the current target."
  [session target]
  {:pre [(base-session? session) (map? target)]
   :post [(target-session? %)]}
  (assoc session :target target))

(ann target [Session -> TargetMap])
(defn target
  "Current session target map."
  [session]
  {:pre [(target-session? session)]
   :post [(map? %)]}
  (-> session :target))

(defn file-uploader
  [session]
  (-> session :execution-state :action-options :file-uploader))
