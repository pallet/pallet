(ns pallet.crate
  "# Pallet Crate Writing API"
  (:require
   [clojure.string :as string]
   [pallet.core.plan-state :as plan-state]
   [pallet.core.session :as session]
   [pallet.execute :as execute]
   [pallet.node :as node])
  (:use
   [clojure.tools.macro :only [name-with-attributes]]
   [pallet.action
    :only [declare-action
           declare-aggregated-crate-action
           declare-collected-crate-action]]
   [pallet.argument :only [delayed-fn]]
   [pallet.context :only [with-phase-context]]
   [pallet.core.session :only [session session!]]
   [pallet.utils :only [compiler-exception local-env]]))


;;; The phase pipeline is used in actions and crate functions. The phase
;;; context automatically sets up the phase context, which is available
;;; (for logging, etc) at phase execution time.
(defmacro phase-context
  "Defines a block with a context that is automatically added."
  {:indent 2}
  [pipeline-name event & args]
  (let [line (-> &form meta :line)]
    `(with-phase-context
       (merge {:kw ~(list 'quote pipeline-name)
               :msg ~(if (symbol? pipeline-name)
                       (name pipeline-name)
                       pipeline-name)
               :ns ~(list 'quote (ns-name *ns*))
               :line ~line
               :log-level :trace}
              ~event)
       ~@args)))

(defn final-fn-sym?
  "Predicate to match the final function symbol in a form."
  [sym form]
  (loop [form form]
    (when (sequential? form)
      (let [s (first form)]
        (if (and (symbol? s) (= sym (symbol (name s))))
          true
          (recur (last form)))))))

(defmacro defplan
  "Define a crate function."
  {:arglists '[[name doc-string? attr-map? [params*] body]]
   :indent 'defun}
  [sym & body]
  (letfn [(output-body [[args & body]]
            (let [no-context? (final-fn-sym? sym body)]
              `(~args
                ;; if the final function call is recursive, then don't add a
                ;; phase-context, so that just forwarding different arities only
                ;; gives one log entry/event, etc.
                ~@(if no-context?
                    body
                    [(let [locals (gensym "locals")]
                       `(let [~locals (local-env)]
                          (phase-context
                              ~(symbol (str (name sym) "-cfn"))
                              {:msg ~(str sym)
                               :kw ~(keyword sym)
                               :locals ~locals}
                            ~@body)))]))))]
    (let [[sym rest] (name-with-attributes sym body)
          sym (vary-meta sym assoc :pallet/plan-fn true)]
      (if (vector? (first rest))
        `(defn ~sym
           ~@(output-body rest))
        `(defn ~sym
           ~@(map output-body rest))))))

(defmacro def-aggregate-plan-fn
  "Define a crate function where arguments on successive calls are conjoined,
   and passed to the function specified in the body."
  {:arglists '[[name doc-string? attr-map? [params*] f]]
   :indent 'defun}
  [sym & args]
  (let [[sym [args f & rest]] (name-with-attributes sym args)
        sym (vary-meta sym assoc :pallet/plan-fn true)
        id (gensym (name sym))]
    (when (seq rest)
      (throw (compiler-exception
              (IllegalArgumentException.
               (format
                "Extra arguments passed to def-aggregate-plan-fn: %s"
                (vec rest))))))
    `(let [action# (declare-aggregated-crate-action '~sym ~f)]
       (defplan ~sym
         [~@args]
         (action# ~@args)))))

(defmacro def-collect-plan-fn
  "Define a crate function where arguments on successive calls are conjoined,
   and passed to the function specified in the body."
  {:arglists '[[name doc-string? attr-map? [params*] f]]
   :indent 'defun}
  [sym & args]
  (let [[sym [args f & rest]] (name-with-attributes sym args)
        sym (vary-meta sym assoc :pallet/plan-fn true)
        id (gensym (name sym))]
    (when (seq rest)
      (throw (compiler-exception
              (IllegalArgumentException.
               (format
                "Extra arguments passed to def-collect-plan-fn: %s"
                (vec rest))))))
    `(let [action# (declare-collected-crate-action '~sym ~f)]
       (defplan ~sym
         [~@args]
         (action# ~@args)))))

;;; Multi-method for plan functions
(defmacro defmulti-plan
  "Declare a multimethod for plan functions"
  {:arglists '([name docstring? attr-map? dispatch-fn
                & {:keys [hierarchy] :as options}])}
  [name & args]
  (let [[docstring args] (if (string? (first args))
                           [(first args) (rest args)]
                           [nil args])
        [attr-map args] (if (map? (first args))
                          [(first args) (rest args)]
                          [nil args])
        dispatch-fn (first args)
        {:keys [hierarchy]
         :or {hierarchy #'clojure.core/global-hierarchy}} (rest args)
        args (first (filter vector? dispatch-fn))
        name (vary-meta name assoc :pallet/plan-fn true)]
    `(let [a# (atom {})]
       (def
         ~name
         ^{:dispatch-fn (fn [~@args] ~@(rest dispatch-fn))
           :methods a#}
         (fn [~@args]
           (let [dispatch-val# ((-> ~name meta :dispatch-fn) ~@args)]
             (if-let [f# (or (get @a# dispatch-val#)
                             (some
                              (fn [[k# f#]]
                                (when (isa? @~hierarchy dispatch-val# k#)
                                  f#))
                              @a#))]
               (f# ~@args)
               (throw
                (ex-info
                 (format "Missing plan-multi %s dispatch for %s"
                         ~(clojure.core/name name) (pr-str dispatch-val#))
                 {:reason :missing-method
                  :plan-multi ~(clojure.core/name name)})))))))))

(defn
  ^{:internal true :indent 2}
  add-plan-method-to-multi
  [multifn dispatch-val f]
  (swap! (-> multifn meta :methods) assoc dispatch-val f))

(defmacro defmethod-plan
  {:indent 2}
  [multifn dispatch-val args & body]
  (letfn [(sanitise [v]
            (string/replace (str v) #":" ""))]
    `(add-plan-method-to-multi ~multifn ~dispatch-val
       (fn [~@args]
         (phase-context
             ~(symbol (str (name multifn) "-" (sanitise dispatch-val)))
             {:msg ~(name multifn) :kw ~(keyword (name multifn))
              :dispatch-val ~dispatch-val}
           ~@body)))))

;;;  helpers
(defmacro session-peek-fn
  "Create a state-m monadic value function that examines the session, and
  returns nil."
  {:indent 'defun}
  [[sym] & body]
  `(fn session-peek-fn [~sym]
     ~@body
     [nil ~sym]))

;;; ## Session Accessors
(defn target
  "The target-node map."
  []
  (session/target (session)))

(defn target-node
  "The target-node instance (the :node in the target-node map)."
  []
  (session/target-node (session)))

(defn targets
  "All targets."
  []
  (session/targets (session)))

(defn target-nodes
  "All target-nodes."
  []
  (session/target-nodes (session)))

(defn target-id
  "Id of the target-node (unique for provider)."
  []
  (session/target-id (session)))

(defn target-name
  "Name of the target-node."
  []
  (node/hostname (session/target-node (session))))

(defn admin-user
  "Id of the target-node."
  []
  (session/admin-user (session)))

(defn os-family
  "OS-Family of the target-node."
  []
  (session/os-family (session)))

(defn os-version
  "OS-Family of the target-node."
  []
  (session/os-version (session)))

(defn group-name
  "Group-Name of the target-node."
  []
  (session/group-name session))

(defn nodes-in-group
  "All nodes in the same tag as the target-node, or with the specified
  group-name."
  ([group-name]
     (session/nodes-in-group (session) group-name))
  ([]
     (nodes-in-group (group-name))))

(comment
  (defn groups-with-role
    "All target groups with the specified role."
    [role]
    (session/groups-with-role (session) role)))

(defn nodes-with-role
  "All target nodes with the specified role."
  [role]
  (session/nodes-with-role (session) role))

(defn role->nodes-map
  "A map from role to nodes."
  []
  (session/role->nodes-map (session)))

(defn packager
  []
  (session/packager (session)))

(defn admin-group
  "User that remote commands are run under"
  []
  (session/admin-group (session)))

(defn is-64bit?
  "Predicate for a 64 bit target"
  []
  (session/is-64bit? (session)))

(defn compute-service
  "Returns the current compute service"
  []
  (if-let [node (session/target-node (session))]
    (node/compute-service node)
    (:compute session)))

(defn target-flag?
  "Returns a DelayedFunction that is a predicate for whether the flag is set"
  {:pallet/plan-fn true}
  [flag]
  (delayed-fn #(execute/target-flag? % (keyword (name flag)))))

;;; ## Settings
(defn get-settings
  "Retrieve the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility. If passed a nil
   `instance-id`, then `:default` is used"
  ([facility {:keys [instance-id default] :as options}]
     (plan-state/get-settings
      (:plan-state (session)) (session/target-id (session)) facility options))
  ([facility]
     (get-settings facility {})))

(defn get-node-settings
  "Retrieve the settings for the `facility` on the `node`. The instance-id
   allows the specification of specific instance of the facility. If passed a
   nil `instance-id`, then `:default` is used"
  ([node facility {:keys [instance-id default] :as options}]
     (plan-state/get-settings
      (:plan-state (session)) (node/id node) facility options))
  ([node facility]
     (get-node-settings node facility {})))

(defn assoc-settings
  "Set the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility (the default is
   :default)."
  ([facility kv-pairs {:keys [instance-id] :as options}]
     (session!
      (update-in
       (session) [:plan-state]
       plan-state/assoc-settings
       (session/target-id (session)) facility kv-pairs options)))
  ([facility kv-pairs]
     (assoc-settings facility kv-pairs {})))

(defn update-settings
  "Update the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility (the default is
   :default)."
  {:arglists '[[facility f & args][facility options f & args]]}
  [facility f-or-opts & args]
  (let [[options f args] (if (map? f-or-opts)
                           [f-or-opts (first args) (rest args)]
                           [nil f-or-opts args])]

    (session!
     (update-in
      (session) [:plan-state]
      plan-state/update-settings
      (session/target-id (session)) facility f args options))))
