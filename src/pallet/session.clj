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
   [pallet.core.executor :refer [executor?]]
   [pallet.core.plan-state
    :refer [get-settings get-scopes merge-scopes plan-state?]]
   [pallet.core.plan-state.protocols :refer [StateGet]]
   [pallet.core.recorder :refer [recorder?]]
   [pallet.core.recorder.protocols :refer [Record]]
   [pallet.core.recorder.null :refer [null-recorder]]
   [pallet.user :refer [user?]]
   [pallet.utils :as utils]
   [schema.core :as schema :refer [check required-key optional-key validate]]))


(def execution-state
  {:executor pallet.core.executor.protocols.ActionExecutor
   (optional-key :recorder) pallet.core.recorder.protocols.Record
   (optional-key :action-options) {schema/Keyword schema/Any}
   (optional-key :user) schema/Any
   (optional-key :environment) schema/Any
   (optional-key :extension) {schema/Keyword schema/Any}})

(def base-session
  {:execution-state execution-state
   (optional-key :plan-state) pallet.core.plan_state.protocols.StateGet
   :type (schema/eq ::session)})

(def target-session
  (assoc base-session :target {schema/Keyword schema/Any}))

(defn base-session?
  [x]
  (not (schema/check base-session x)))

(defn target-session?
  [x]
  (not (schema/check target-session x)))

(defn validate-target-session
  [x]
  (schema/validate target-session x))

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


(defn plan-state
  "Get the plan state"
  [session]
  (:plan-state session))

(defn recorder
  "Get the action recorder"
  [session]
  (-> session :execution-state :recorder))

(defn executor
  "Get the action executor."
  [session]
  {:pre [(or (base-session? session) (target-session? session))]
   :post [(executor? %)]}
  (-> session :execution-state :executor))

(defn set-executor
  "Get the action executor."
  [session executor]
  {:pre [(executor? executor)]}
  (assoc-in session [:execution-state :executor] executor))

(defn action-options
  "Get the action options."
  [session]
  (-> session :execution-state :action-options))

(defn merge-action-options
  "Update the default action options modifiers defined on the session"
  [session m]
  (update-in session [:execution-state :action-options] merge m))

(defn ^:internal environment
  "Get the environment map."
  [session]
  (-> session :execution-state :environment))

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

(defn user
  "Return a session with `user` as the known admin user."
  [session]
  {:post [(user? %)]}
  (-> session :execution-state :user))

(defn set-user
  "Return a session with `user` as the known admin user."
  [session user]
  {:pre [(user? user)]}
  (assoc-in session [:execution-state :user] user))

(defn set-recorder
  "Return a session with `recorder` as the action result recorder."
  [session recorder]
  {:post [(recorder? (pallet.session/recorder %))]}
  (assoc-in session [:execution-state :recorder] recorder))

(defn set-target
  "Return a session with `:target` as the current target."
  [session target]
  {:pre [(base-session? session) (map? target)]
   :post [(target-session? %)]}
  (assoc session :target target))

(defn target
  "Current session target map."
  [session]
  {:pre [(target-session? session)]
   :post [(map? %)]}
  (-> session :target))
