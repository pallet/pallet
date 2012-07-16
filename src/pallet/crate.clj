(ns pallet.crate
  "# Pallet Crate Writing API"
  (:require
   [clojure.string :as string]
   [pallet.core.plan-state :as plan-state]
   [pallet.core.session :as session])
  (:use
   [clojure.tools.macro :only [name-with-attributes]]
   [pallet.action :only [declare-aggregated-crate-action declare-action]]
   [pallet.monad :only [phase-pipeline phase-pipeline-no-context
                        session-pipeline local-env let-s]]
   [pallet.utils :only [compiler-exception]]
   [slingshot.slingshot :only [throw+]]))

(defmacro defplan
  "Define a crate function."
  {:indent 'defun}
  [sym & body]
  (let [docstring (when (string? (first body)) (first body))
        body (if docstring (rest body) body)
        sym (if docstring (vary-meta sym assoc :doc docstring) sym)]
    `(def ~sym
       (let [locals# (local-env)]
         (phase-pipeline
             ~(symbol (str (name sym) "-cfn"))
             {:msg ~(str sym) :kw ~(keyword sym) :locals locals#}
           ~@body)))))

(defmacro def-plan-fn
  "Define a crate function."
  {:arglists '[[name doc-string? attr-map? [params*] body]]
   :indent 'defun}
  [sym & body]
  (letfn [(output-body [[args & body]]
            (let [p (if (and (sequential? (last body))
                             (symbol? (first (last body)))
                             (= sym (symbol (name (first (last body))))))
                      `phase-pipeline-no-context ; ends in recursive call
                      `phase-pipeline)]
              `(~args
                (let [locals# (local-env)]
                  (~p
                      ~(symbol (str (name sym) "-cfn"))
                      {:msg ~(str sym) :kw ~(keyword sym) :locals locals#}
                    ~@body)))))]
    (let [[sym rest] (name-with-attributes sym body)]
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
        id (gensym (name sym))]
    (when (seq rest)
      (throw (compiler-exception
              (IllegalArgumentException.
               (format
                "Extra arguments passed to def-aggregate-crate-fn: %s"
                (vec rest))))))
    `(let [action# (declare-aggregated-crate-action '~sym ~f)]
       (def-plan-fn ~sym
         ;; ~(merge
         ;;   {:execution :aggregated-crate-fn
         ;;    :crate-fn-id (list 'quote id)
         ;;    :action-name (list 'quote sym)}
         ;;   (meta sym))
         [~@args]
         (action# ~@args)))))

;;; Multi-method for plan functions
(defmacro defmulti-plan
  "Declare a multimethod for plan functions"
  {:arglists '([name docstring? attr-map? dispatch-fn & options])}
  [name & args]
  (let [[docstring args] (if (string? (first args))
                           [(first args) (rest args)]
                           [nil args])
        [attr-map args] (if (map? (first args))
                          [(first args) (rest args)]
                          [nil args])
        dispatch-fn (first args)
        args (first (filter vector? dispatch-fn))]
    `(let [a# (atom {})]
       (def
         ~name
         ^{:dispatch-fn (fn [~@args] ~@(rest dispatch-fn))
           :methods a#}
         (fn [~@args]
           (let [df# ((-> ~name meta :dispatch-fn) ~@args)]
             (fn [session#]
               (let [[dispatch-val# _#] (df# session#)]
                 (if-let [f# (get @a# dispatch-val#)]
                   ((f# ~@args) session#)
                   (throw+
                    {:reason :missing-method
                     :plan-multi ~(clojure.core/name name)
                     :session session#}
                    "Missing plan-multi %s dispatch for %s"
                    ~(clojure.core/name name)
                     (pr-str dispatch-val#)))))))))))

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
         (phase-pipeline
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

(defn target-id
  "Id of the target-node (unique for provider)."
  [session]
  [(session/target-id session) session])

(defn admin-user
  "Id of the target-node (unique for provider)."
  [session]
  [(session/admin-user session) session])

(defn os-family
  "OS-Family of the target-node."
  [session]
  [(session/os-family session) session])

(defn os-version
  "OS-Family of the target-node."
  [session]
  [(session/os-version session) session])

(defn group-name
  "Group-Name of the target-node."
  [session]
  [(session/group-name session) session])

(defn nodes-in-group
  "All nodes in the same tag as the target-node, or with the specified
  group-name."
  ([group-name]
     (fn [session]
       [(session/nodes-in-group session group-name) session]))
  ([]
     (fn [session]
       [(session/nodes-in-group) session])))

(comment
  (defn groups-with-role
    "All target groups with the specified role."
    [role]
    (fn [session]
      [(session/groups-with-role session role) session])))

(defn nodes-with-role
  "All target nodes with the specified role."
  [role]
  (fn [session]
    [(session/nodes-with-role session role) session]))

(defn packager
  [session]
  [(session/packager session) session])

(defn admin-group
  "User that remote commands are run under"
  [session]
  [(session/admin-group session) session])

(defn is-64bit?
  "Predicate for a 64 bit target"
  [session]
  [(session/is-64bit? session) session])

;;; ## Settings
(defn get-settings
  "Retrieve the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility. If passed a nil
   `instance-id`, then `:default` is used"
  ([facility {:keys [instance-id default] :as options}]
     (fn [session]
       [(plan-state/get-settings
         (:plan-state session) (session/target-id session) facility options)
        session]))
  ([facility]
     (get-settings facility {})))

(defn assoc-settings
  "Set the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility (the default is
   :default)."
  ([facility kv-pairs {:keys [instance-id] :as options}]
     (fn [session]
       [session
        (update-in
         session [:plan-state]
         plan-state/assoc-settings
         (session/target-id session) facility kv-pairs options)]))
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
    (fn [session]
      [session
       (update-in
        session [:plan-state]
        plan-state/update-settings
        (session/target-id session) facility f args options)])))
