(ns pallet.action
  "Actions are the primitives that are called by crate functions.

   Actions can have multiple implementations, but by default most are
   implemented as script to be executed on the remote node.

   An action has an :execution, which is one of :aggregated, :in-sequence,
   :collected, :delayed-crate-fn or :aggregated-crate-fn.

   Calls to :aggregated actions will be grouped, and run before
   :in-sequence actions. Calls to :collected actions will be grouped, and run
   after :in-sequence actions.

   Calls to :delayed-crate-fn and :aggregated-crate-fn actions are evaluated
   at action plan translation time, which provides a mechanism for calling
   crate functions after all other crate functions have been called."
  (:require
   [clojure.tools.macro :refer [name-with-attributes]]
   [pallet.action-impl
    :refer [action-implementation
            action-symbol
            add-action-implementation!
            make-action]]
   [pallet.action-plan
    :refer [action-map pop-block push-block schedule-action-map]]
   [pallet.common.context :refer [throw-map]]
   [pallet.core.session :refer [session session!]]
   [pallet.session.action-plan
    :refer [assoc-action-plan get-action-plan update-action-plan]]
   [pallet.stevedore :refer [with-source-line-comments]]
   [pallet.utils :refer [compiler-exception]]))

;;; # Session precedence

;;; Precedence for actions can be overridden by setting the precedence map
;;; on the session.
(def ^{:no-doc true :internal true} action-options-key ::action-options)

(defn action-options
  "Return any action-options currently defined on the session."
  [session]
  (get-in session [:plan-state action-options-key]))

(defn get-action-options
  "Return any action-options currently defined on the session."
  []
  (action-options (session)))

(defn update-action-options
  "Update any precedence modifiers defined on the session"
  [m]
  (session! (update-in (session) [:plan-state action-options-key] merge m)))

(defn assoc-action-options
  "Set precedence modifiers defined on the session."
  [m]
  (session! (assoc-in (session) [:plan-state action-options-key] m)))

(defmacro ^{:indent 1} with-action-options
  "Set up local precedence relations between actions, and allows override
of user options.

`:script-dir`
: Controls the directory the script is executed in.

`:sudo-user`
: Controls the user the action runs as.

`:script-prefix`
: Specify a prefix for the script. Disable sudo using `:no-sudo`. Defaults to
  `:sudo`.

`:script-env`
: Specify a map of environment variables.

`:script-comments`
: Control the generation of script line number comments

`:new-login-after-action`
: Force a new ssh login after the action.  Useful if the action effects the
  login environment and you want the affect to be visible immediately."
  [m & body]
  `(let [p# (get-action-options)
         m# ~m]
     (update-action-options m#)
     (let [v# (do ~@body)]
       (assoc-action-options p#)
       v#)))


;;; ## Actions

;;; Actions are primitives that may be called in a phase or crate function. An
;;; action can have multiple implementations. At this level, actions are
;;; represented by the function that inserts an action map for the action into
;;; the action plan.  This inserter function has the action on its :action
;;; metadata key.

(defn insert-action
  "Registers an action map in the action plan for execution. This function is
   responsible for creating a node-value (as node-value-path's have to be unique
   for all instances of an aggregated action) as a handle to the value that will
   be returned when the action map is executed."
  [session action-map]
  {:pre [session (map? session)]}
  (let [[node-value action-plan] (schedule-action-map
                                  (get-action-plan session) action-map)]
    [node-value (assoc-action-plan session action-plan)]))

(defn- action-inserter-fn
  "Return an action inserter function. This is used for anonymous actions. The
  argument list is not enforced."
  [action]
  ^{:action action
    :pallet/plan-fn true}
  (fn action-fn [& argv]
    (let [session (session)
          [nv session] (insert-action
                        session
                        (action-map action argv (action-options session)))]
      (session! session)
      nv)))

(defn declare-action
  "Declare an action. The action-name is a symbol (not necessarily referring to
  a Var).

   The execution model can be specified using the :execution key in `metadata`,
   with one of the following possible values:

   :in-sequence - The generated action will be applied to the node
        \"in order\", as it is defined lexically in the source crate.
        This is the default.
   :aggregated - All aggregated actions are applied to the node
        in the order they are defined, but before all :in-sequence
        actions. Note that all of the arguments to any given
        action function are gathered such that there is only ever one
        invocation of each fn within each phase.
   :collected - All collected actions are applied to the node
        in the order they are defined, but after all :in-sequence
        action. Note that all of the arguments to any given
        action function are gathered such that there is only ever one
        invocation of each fn within each phase.
   :delayed-crate-fn - delayed crate functions are phase functions that
        are executed at action-plan translation time.
   :aggregated-crate-fn - aggregate crate functions are phase functions that are
        executed at action-plan translation time, with aggregated arguments, as
        for :aggregated."
  [action-symbol metadata]
  (let [action (make-action
                action-symbol
                (:execution metadata :in-sequence)
                (dissoc metadata :execution))]
    (action-inserter-fn action)))

(defn- args-with-map-as
  "Ensures that an argument map contains an :as element, by which the map can be
  referenced."
  [args]
  (map #(if (map? %) (merge {:as (gensym "as")} %) %) args))

(defn- arg-values
  "Converts a symbolic argument list into a compatible argument vector for
   passing to a function with the same signature."
  [symbolic-args]
  (let [{:keys [args &-seen] :as result}
        (reduce
         (fn [{:keys [args &-seen] :as result} arg]
           (cond
             (= arg '&) (assoc result :&-seen true)
             (map? arg) (if &-seen
                          (update-in
                           result [:args] conj (list `apply `concat (:as arg)))
                          (update-in result [:args] conj (:as arg)))
             :else (update-in result [:args] conj arg)))
         {:args [] :&-seen false}
         symbolic-args)]
    (concat (if &-seen [`apply `vector] [`vector]) args)))


;; This doesn't use declare-action, so that the action inserter function
;; gets the correct signature.
(defmacro defaction
  "Declares a named action."
  {:arglists '[[action-name attr-map? [params*]]]
   :indent 'defun}
  [action-name & body]
  (let [[action-name [args & body]] (name-with-attributes action-name body)
        action-name (vary-meta
                     action-name assoc
                     :arglists (list 'quote [args])
                     :defonce true
                     :pallet/action true
                     :pallet/plan-fn true)
        action-symbol (symbol
                       (or (namespace action-name) (name (ns-name *ns*)))
                       (name action-name))
        metadata (meta action-name)
        args (args-with-map-as args)]
    `(let [action# (make-action
                    '~action-symbol
                    ~(:execution metadata :in-sequence)
                    ~(select-keys metadata [:always-before :always-after]))]
       (defonce ~action-name
         ^{:action action#}
         (fn ~action-name
           [~@args]
           (let [session# (session)
                 [nv# session#] (insert-action
                                 session#
                                 (action-map
                                  action#
                                  ~(arg-values args)
                                  (action-options session#)))]
             (session! session#)
             nv#))))))

(defn implement-action*
  "Define an implementation of an action given the `action-inserter` function."
  [action-inserter dispatch-val metadata f]
  {:pre [(fn? action-inserter) (-> action-inserter meta :action) (fn? f)]}
  (let [action (-> action-inserter meta :action)]
    (when-not (keyword? dispatch-val)
      (throw
       (compiler-exception
        (IllegalArgumentException.
         (format
          "Attempting to implement action %s with invalid dispatch value %s"
          (action-symbol action) dispatch-val)))))
    (add-action-implementation! action dispatch-val metadata f)))

(defmacro implement-action
  "Define an implementation of an action. The dispatch-val is used to dispatch
  on."
  {:arglists '[[action-name dispatch-val attr-map? [params*]]]
   :indent 2}
  [action-inserter dispatch-val & body]
  (let [[impl-name [args & body]] (name-with-attributes action-inserter body)]
    `(let [inserter# ~action-inserter]
       ;; (when-not (-> inserter# meta :action)
       ;;   (throw
       ;;    (compiler-exception
       ;;     (IllegalArgumentException.
       ;;      "action-inserter has no :action metadata"))))
       (implement-action*
        inserter# ~dispatch-val
        ~(meta impl-name)
        (fn ~(symbol (str impl-name "-" (name dispatch-val)))
          [~@args] ~@body)))))

(defn implementation
  "Returns the metadata and function for an implementation of an action from an
  action map."
  [{:keys [action] :as action-map} dispatch-val]
  (let [m (action-implementation action dispatch-val)]
    (if m
      m
      (throw-map
       (format
        "No implementation of type %s found for action %s"
        dispatch-val (action-symbol action))
       {:dispatch-val dispatch-val
        :action-name (action-symbol action)
        :action-map action-map}))))

(defn action-fn
  "Returns the function for an implementation of an action from an action
   inserter."
  [action-inserter dispatch-val]
  (let [action (-> action-inserter meta :action)
        m (action-implementation action dispatch-val)]
    (if m
      (:f m)
      (throw-map
       (format
        "No implementation of type %s found for action %s"
        dispatch-val (action-symbol action))
       {:dispatch-val dispatch-val
        :action action}))))

(defn declare-delayed-crate-action
  "Declare an action for a delayed crate function. A delayed crate function
   becomes an action with a single, :default, implementation."
  [sym f]
  (let [action (declare-action sym {:execution :delayed-crate-fn})]
    (implement-action* action :default {} f)
    action))

(defn declare-aggregated-crate-action
  "Declare an action for an aggregated crate function. A delayed crate function
   becomes an action with a single, :default, implementation."
  [sym f]
  (let [action (declare-action sym {:execution :aggregated-crate-fn})]
    (implement-action* action :default {} f)
    action))

(defn declare-collected-crate-action
  "Declare an action for an collected crate function. A delayed crate function
   becomes an action with a single, :default, implementation."
  [sym f]
  (let [action (declare-action sym {:execution :collected-crate-fn})]
    (implement-action* action :default {} f)
    action))

(defmacro clj-action-fn
  "Creates a clojure action with a :direct implementation. The first argument
will be the session. The clojure code can not return a modified session (use a
full action to do that."
  {:indent 1} [args & impl]
  (let [action-sym (gensym "clj-action")]
    `(let [action# (declare-action '~action-sym {})]
       (implement-action action# :direct
         {:action-type :fn/clojure :location :origin}
         ~args
         [(fn ~action-sym [~(first args)] ~@impl) ~(first args)])
       action#)))

(defmacro clj-action
  "Creates a clojure action with a :direct implementation."
  {:indent 1}
  [args & impl]
  (let [action-sym (gensym "clj-action")]
    `(let [action# (declare-action '~action-sym {})]
       (implement-action action# :direct
         {:action-type :fn/clojure :location :origin}
         ~args
         [(fn ~action-sym [~(first args)] ~@impl) ~(first args)])
       action#)))

(defn enter-scope
  "Enter a new action scope."
  []
  (session! (update-action-plan (session) push-block)))

(defn leave-scope
  "Leave the current action scope."
  []
  (session! (update-action-plan (session) pop-block)))

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (clj-action 'defun)(implement-action 4))
;; End:
