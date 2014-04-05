(ns pallet.action
  "Actions are the primitives that are called by phase functions.

   Actions can have multiple implementations, but by default most are
   implemented as script."
  (:require
   [taoensso.timbre :refer [debugf]]
   [clojure.tools.macro :refer [name-with-attributes]]
   [pallet.action.impl
    :refer [action-implementation
            action-symbol
            add-action-implementation!
            make-action]]
   [pallet.action-options :refer [action-options]]
   [pallet.common.context :refer [throw-map]]
   [pallet.plan :refer [execute-action]]
   [pallet.session :as session :refer [validate-target-session]]
   [pallet.stevedore :refer [with-source-line-comments]]
   [pallet.target-info :refer [admin-user]]
   [pallet.utils :refer [maybe-assoc multi-fn?]]))

;;; ## Actions
(defn action-map
  "Return an action map for the given `action` and `args`. The action
   map is an instance of an action.  The returned map has the
   following keys:

   - :action          the action that is scheduled,
   - :args            the arguments to pass to the action function,"
  [action arg-vector options]
  {:action action
   :args arg-vector
   :options options})

(defn effective-user
  "Return a user map for the effective user for an action."
  [user action-options]
  (let [user (merge user (:user action-options))
        no-sudo (or (:no-sudo action-options)
                    (and (:no-sudo user) (not (:sudo-user action-options))))
        sudo-user (and (not no-sudo)
                       (or (:sudo-user action-options)
                           (:sudo-user user)))
        username (or (:username action-options)
                     (:username user))]
    (maybe-assoc user
                 :no-sudo no-sudo
                 :sudo-user sudo-user
                 :username username)))

(defn action-execute
  "Call the session executor for the action with argv."
  [session action argv]
  {:pre [(validate-target-session session)]}
  (let [options (merge (:options action) (action-options session))
        user (effective-user (admin-user session) options)
        options (update-in options [:user] merge user)]
    (debugf "effective-user %s %s %s" (admin-user session) options user)
    (execute-action session (action-map action argv options))))

(defn- action-execute-fn
  "Return a function that will call the session executor for the
  action with argv."
  [action]
  (with-meta
    (fn action-fn [session & argv]
      (action-execute session action argv))
    {:action action}))

(defn declare-action
  "Declare an action. The action-name is a symbol (not necessarily referring to
  a Var).  Returns an executor function for the action."
  [action-symbol options]
  (let [action (make-action action-symbol options)]
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


;;; We want defonce semantics, so that the action implementations
;;; aren't lost when the action is recompiled.

;;; This doesn't use declare-action, so that the action inserter
;;; function gets the correct signature.

;;; TODO find a way to do this while still getting a top level
;;; function, rather than an anonymous function.

(defmacro defaction
  "Declares a named action.  The action has defonce semantics, so
  implementations are not lost on recompiles."
  {:arglists '[[action-name attr-map? [params*]]]
   :indent 'defun}
  [action-name & body]
  (let [[action-name [args & body]] (name-with-attributes action-name body)
        action-meta (dissoc (meta action-name) :doc :arglists)
        action-name (vary-meta
                     action-name assoc
                     :arglists (list 'quote [args])
                     :defonce true)
        action-symbol (symbol
                       (or (namespace action-name) (name (ns-name *ns*)))
                       (name action-name))
        metadata (meta action-name)
        args (args-with-map-as args)]
    (with-meta
      `(let [action# (make-action '~action-symbol ~action-meta)]
         (defonce ~action-name
           ^{:action action#}
           (fn [~@args]
             (let [session# ~(first args)]
               (action-execute session# action# ~(arg-values (rest args)))))))
      (meta &form))))

(defn implement-action
  "Define an implementation of an action given the `action-inserter`
  action function."
  ([action-inserter dispatch-val metadata script-data f]
     {:pre [action-inserter
            (or (fn? f) (multi-fn? f))]}
     (let [action (-> action-inserter meta :action)]
       (when-not action
         (throw
          (ex-info "Trying to implement action on a non-action var"
                   {:action action-inserter})))
       (add-action-implementation!
        action dispatch-val metadata
        (fn [& args]
          [script-data (apply f args)]))))
  ([action-inserter dispatch-val metadata f]
     {:pre [action-inserter
            (or (fn? f) (multi-fn? f))]}
     (let [action (-> action-inserter meta :action)]
       (when-not action
         (throw
          (ex-info "Trying to implement action on a non-action var"
                   {:action action-inserter})))
       (add-action-implementation!
        action dispatch-val metadata
        (fn [& args]
          (apply f args))))))

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
