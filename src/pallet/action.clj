(ns pallet.action
  "Actions are the primitives that are called by crate functions.

   Actions can have multiple implementations, but by default most are
   implemented as script."
  (:require
   [clojure.tools.macro :refer [name-with-attributes]]
   [pallet.action.impl
    :refer [action-implementation
            action-symbol
            add-action-implementation!
            make-action]]
   [pallet.action-options :refer [action-options]]
   [pallet.common.context :refer [throw-map]]
   [pallet.plan :refer [execute-action]]
   [pallet.session :refer [target-session?]]
   [pallet.stevedore :refer [with-source-line-comments]]
   [pallet.utils :refer [compiler-exception]]))

;;; ## Actions
(defn action-map
  "Return an action map for the given `action` and `args`. The action
   map is an instance of an action.  The returned map has the
   following keys:

   - :action          the action that is scheduled,
   - :args            the arguments to pass to the action function,"
  [action arg-vector options]
  (merge options {:action action :args arg-vector}))

;;; Actions are primitives that may be called in a phase or crate function. An
;;; action can have multiple implementations.
(defn- action-execute-fn
  "Return an action inserter function. This is used for anonymous actions. The
  argument list is not enforced."
  [action]
  ^{:action action}
  (fn action-fn [session & argv]
    (execute-action
     session
     (action-map action argv (action-options session)))))

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
    (action-execute-fn action)))

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
           (let [session# ~(first args)]
             (assert (target-session? session#) "Invalid session")
             (execute-action
              session#
              (action-map
               action# ~(arg-values args) (action-options session#)))))))))

(defn implement-action*
  "Define an implementation of an action given the `action-inserter` function."
  [action-inserter dispatch-val metadata f]
  {:pre [(fn? f)]}
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
  [action dispatch-val & body]
  (let [[impl-name [args & body]] (name-with-attributes action body)]
    `(let [action# ~action]
       (implement-action*
        action# ~dispatch-val
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
  [action dispatch-val]
  (let [action (-> action meta :action)
        m (action-implementation action dispatch-val)]
    (if m
      (:f m)
      (throw-map
       (format
        "No implementation of type %s found for action %s"
        dispatch-val (action-symbol action))
       {:dispatch-val dispatch-val
        :action action}))))

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (defaction 'defun)(implement-action 3))
;; End:
