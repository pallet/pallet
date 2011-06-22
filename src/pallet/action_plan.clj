(ns pallet.action-plan
  "An action plan contains actions for execution.

   The action plan is built by executing a phase function. Each phase function
   calls actions which insert themselves into the action plan.

   The action plan is transformed to provide aggregated operations, and to
   resolve precedence relations between actions.

   A translated plan is executed by passing an executor, which is a map
   from action type to function.  The executor functions are called with the
   result of evaluating the action with it's arguments."
  {:author "Hugo Duncan"}
  (:require
   [pallet.argument :as argument]
   [pallet.phase :as phase]
   [pallet.script :as script]
   [pallet.session :as session]
   [clojure.contrib.condition :as condition]
   [clojure.contrib.logging :as logging]
   [clojure.set :as set]
   [clojure.string :as string])
  (:use
   [clojure.contrib.def :only [defunbound defvar defvar- name-with-attributes]]
   clojure.contrib.core))

;; The action plan is a stack of actions, where the action could itself
;; be a stack of actions (ie a tree of stacks)

(defn push-block
  "Push a block onto the action-plan"
  [action-plan]
  (conj (or action-plan '(nil nil)) nil))

(defn pop-block
  "Take the last block and add it to the scope below it in the stack.
   The block is reversed to put it into the order in which elements
   were added. Once pop'd, nothing should be added to the block."
  [action-plan]
  (let [block (peek action-plan)
        stack (pop action-plan)]
    (if-let [stem (next stack)]
      (conj stem (conj (first stack) (reverse block)))
      (if-let [stem (seq (first stack))]
        (conj stem (reverse block))
        (reverse block)))))

(defn add-action
  "Add an action to the plan"
  [action-plan action]
  (let [action-plan (or action-plan '(nil nil))
        block (peek action-plan)
        stack (pop action-plan)]
    (conj stack (conj block action))))

;; pallet specific action
(defn action-map
  "Return an action map for the given args. The action plan is a tree of
   action maps.

   precedence specifies naming and dependencies, with :action-id, :always-before
   and :always-after. If a precedence is supplied, an action-id is generated
   if none present, to ensure that the standard action precedence is not
   altered.

   - :f            the action function
   - :args         the arguments to pass to the action function
   - :location     where to execute the action - :orgin or :target
   - :action-type  the type of action - :script/bash, :fn/clojure, etc
   - :execution    the execution type - :in-sequence, :aggregated, :collected
   - :value        the result of calling the action function, :f, with :args
   - :session      the session map after calling the action function."
  [action-fn precedence args execution action-type location]
  (let [precedence (and precedence (seq precedence)
                        (update-in precedence [:action-id]
                                   #(or % (gensym "action-id"))))]
    (merge
     (select-keys (meta action-fn) [:action-id :always-after :always-before])
     precedence
     {:f action-fn
      :args args
      :location location
      :action-type action-type
      :execution execution})))

;;; utilities

(defn- script-join
  "Concatenate multiple scripts, removing blank lines"
  [scripts]
  (str
   (->>
    scripts
    (map #(when % (string/trim %)))
    (filter (complement string/blank?))
    (string/join \newline))
   \newline))


;;; transform functions for working with an action-plan containing action-maps
;;; with :nested-scope types

(defn- walk-action-plan
  "Traverses an action-plan structure.  leaf-fn is applied to leaf
   action, list-fn to sequences of actions, and nested-fn to
   a nested scope. nested-fn takes the existing nested scope and a transformed
   arg list"
  [leaf-fn list-fn nested-fn action-plan]
  (cond
   (sequential? action-plan) (list-fn
                              (map
                               #(walk-action-plan leaf-fn list-fn nested-fn %)
                               action-plan))
   (= :nested-scope (:action-type action-plan)) (nested-fn
                                                 action-plan
                                                 (walk-action-plan
                                                  leaf-fn list-fn nested-fn
                                                  (:args action-plan)))
   :else (leaf-fn action-plan)))

;;; transform input nested scopes into action maps with :action-type of
;;; :nested-scope

(defn- scope-action
  "A scope combining action."
  [session & args]
  (script-join (map #((:f %) session) args)))

(defn- nested-scope-transform
  "Transform a nested scope into an action-map with :action-type :nested-scope"
  [x]
  {:pre [(sequential? x)]}
  {:f scope-action
   :args x
   :action-type :nested-scope
   :execution :in-sequence
   :location :target})

(defn transform-nested-scopes
  "Traverses an action-plan structure. Converting nested scopes into
   action-map's."
  [action-plan]
  (cond
   (sequential? action-plan) (nested-scope-transform
                              (vec (map transform-nested-scopes action-plan)))
   :else action-plan))

(defn- transform-scopes
  "Transforms nexted scopes into an action map."
  [action-plan]
  (map transform-nested-scopes action-plan))

;;; transform executions

(defn- group-by-function
  "Transforms a seq of actions, generally some with identical :f values into a
   sequence of actions where the :args are the concatenation of all of the :args
   of associated with each :f in the original seq.  Sequence order from the
   original seq is retained. Keys over than :f and :args are assumed identical
   for a given :f value.

   e.g. (group-by-function
           [{:f :a :args [1 2]}
            {:f :b :args [3 4]}
            {:f :a :args [5 6]}
            {:f :c :args [7 8]]])
        => ({:f :a :args ([1 2] [5 6])}
            {:f :c :args ([7 8])}
            {:f :b :args ([3 4])})"
  [action-plan]
  (->>
   action-plan
   (group-by (juxt :f :action-id))
   (map (fn [[_ action-calls]]
          (reduce
           #(update-in %1 [:args] conj (:args %2))
           (assoc (first action-calls) :args [])
           action-calls)))))

(def ^{:doc "Execution specifc transforms" :private true}
  execution-transforms
  {:aggregated [group-by-function]
   :collected [group-by-function]})

(defvar- execution-ordering [:aggregated :in-sequence :collected])

(defn- transform-execution
  "Transform an execution by applying execution-transforms."
  [execution action-plan]
  (if-let [transforms (execution-transforms execution)]
    (reduce #(%2 %1) action-plan transforms)
    action-plan))

(defn- transform-scope-executions
  "Sort an action plan scope into different executions, applying execution
   specific transforms."
  [action-plan]
  (let [executions (group-by :execution action-plan)]
    (mapcat
     #(transform-execution % (% executions))
     execution-ordering)))

(defn- transform-executions
  "Sort an action plan into different executions, applying execution specific
   transforms."
  [action-plan]
  (walk-action-plan
   identity
   transform-scope-executions
   #(assoc %1 :args %2)
   action-plan))

;;; enforce declared precedence rules
(defn- symbol-action-fn
  "Lookup the action-fn from a symbol"
  [sym]
  (if-let [v (find-var sym)]
    (-> v var-get meta :pallet.action/action-fn)))

(defn collect-action-id
  "Extract an action's id to function mapping"
  [m action]
  (if-let [id (:action-id action)]
    (assoc m id (:f action))
    m))

(defn merge-union
  "Merge-with clojure.set/union"
  [& m]
  (apply merge-with set/union m))

(defn action-dependencies
  "Extract an action's dependencies.  Actions are id'd with keywords,
   and dependencies are declared on an action's id or function."
  [action-id-map action]
  (let [as-set (fn [x] (if (or (nil? x) (set? x)) x #{x}))
        before (as-set (:always-before action))
        after (as-set (:always-after action))
        self-id (select-keys action [:action-id :f])]
    (reduce
     (fn [m [id deps]] (update-in m [id] #(conj (or % #{}) deps)))
     {}
     (concat
      ;; before symbol
      (map
       #(vector {:f %} self-id)
       (map symbol-action-fn (filter symbol? before)))
      ;; before id
      (map
       #(vector {:action-id % :f (action-id-map %)} self-id)
       (filter keyword? before))
      ;; after symbol
      (map
       #(vector self-id {:f %})
       (map symbol-action-fn (filter symbol? after)))
      ;; after id
      (map
       #(vector self-id {:action-id % :f (action-id-map %)})
       (filter keyword? after))))))

(defn action-instances
  "Given a map of dependencies, each with an :f and maybe a :action-id,
   returns a map where the values are all matching action instances"
  [actions dependencies]
  (let [action-id-maps (reduce set/union (vals dependencies))]
    (reduce
     (fn [instances instance]
       (let [id (select-keys instance [:f :action-id])]
         (if (action-id-maps id)
           (update-in instances [id] #(conj (or % #{}) instance))
           instances)))
     {}
     actions)))

(defn action-scope-dependencies
  [actions]
  (let [action-id-map (reduce collect-action-id {} actions)
        dependencies (reduce
                      #(merge-union %1 (action-dependencies action-id-map %2))
                      {} actions)
        instances (action-instances actions dependencies)
        dependents (zipmap (keys dependencies)
                           (map
                            (fn [d] (set (mapcat instances d)))
                            (vals dependencies)))]
    [action-id-map dependencies instances dependents]))

(defn action-with-dependents
  [actions dependents seen action]
  {:pre [(vector? actions) (set? seen) (map? action)]}
  (if (seen action)
    [actions dependents seen]
    (let [ids (distinct [(select-keys action [:f :action-id])
                         (select-keys action [:f])])
          action-deps (mapcat dependents ids)]
      (let [[add-actions dependents seen]
            (reduce
             (fn add-a-w-d [[actions dependents seen] action]
               {:pre [(vector? actions) (set? seen) (map? action)]}
               (if (seen action)
                 [actions dependents seen]
                 (action-with-dependents actions dependents seen action)))
             [actions (reduce dissoc dependents ids) seen]
             action-deps)]
        [(conj add-actions action) dependents (conj seen action)]))))

(defn enforce-scope-dependencies
  [actions]
  (let [[action-id-map dependencies instances dependents]
        (action-scope-dependencies actions)]
    (first (reduce
            (fn add-as-w-d [[actions dependents seen] action]
              {:pre [(vector? actions) (set? seen) (map? action)]}
              (if (seen action)
                [actions dependents seen]
                (action-with-dependents actions dependents seen action)))
            [[] dependents #{}]
            actions))))

(defn- enforce-precedence
  "Enforce precedence relations between actions."
  [action-plan]
  (walk-action-plan
   identity
   enforce-scope-dependencies
   #(assoc %1 :args %2)
   action-plan))

;;; convert nested-scopes to script functions

(defn- script-type-scope
  "Convert a scope to a single script function"
  [action-map]
  (if (= :nested-scope (:action-type action-map))
    (assoc action-map :action-type :script/bash :target)
    action-map))

(defn- script-type-scopes-in-scope
  "Reduce a nested scopes of a single scope to a compound action"
  [action-plan]
  (map script-type-scope action-plan))

(defn- script-type-scopes
  "Reduce nested scopes to a compound action"
  [action-plan]
  (walk-action-plan
   identity
   script-type-scopes-in-scope
   (fn [action _] action)
   action-plan))

;;; Bind arguments

(defn- evaluate-args
  "Evaluate an argument sequence"
  [session args]
  (map (fn [arg] (when arg (argument/evaluate arg session))) args))

(defn- apply-action
  "Returns a function that applies args to the function f,
   evaluating the arguments."
  [f args]
  (fn [session]
    (apply f session (evaluate-args session args))))

(defn- apply-aggregated-action
  "Apply args-seq to the function f, evaluating each argument list in args-seq."
  [f args-seq]
  (fn [session]
    (f session (map #(evaluate-args session %) args-seq))))

(defmulti bind-action-arguments
  "Bind an action's arguments."
  (fn [{:keys [execution]}] execution))

(defmethod bind-action-arguments :in-sequence
  [{:keys [f args] :as action-map}]
  (->
   action-map
   (update-in [:f] apply-action args)
   (dissoc :args)))

(defmethod bind-action-arguments :aggregated
  [{:keys [f args] :as action-map}]
  (->
   action-map
   (update-in [:f] apply-aggregated-action args)
   (dissoc :args)))

(defmethod bind-action-arguments :collected
  [{:keys [f args] :as action-map}]
  (->
   action-map
   (update-in [:f] apply-aggregated-action args)
   (dissoc :args)))

(defn- bind-scope-arguments
  "Takes an action plan scope and binds each actions arguments"
  [action-plan]
  (map bind-action-arguments action-plan))

(defn- bind-arguments
  "Takes an action plan and binds each actions arguments"
  [action-plan]
  (walk-action-plan
   identity
   bind-scope-arguments
   #(assoc %1 :args %2)
   action-plan))

;;; combine by location and action-type
(defmulti combine-actions
  "Combine actions by action-type"
  (fn [actions] (:action-type (first actions))))

(defmethod combine-actions :default
  [actions]
  (reduce
   (fn combine-actions-compose [combined action]
     (update-in combined [:f] #(comp (:f action) %)))
   actions))

(defmethod combine-actions :script/bash
  [actions]
  (assoc (first actions)
    :f (fn [session] (script-join (map #((:f %) session) actions)))))

(defmethod combine-actions :transfer/to-local
  [actions]
  (assoc (first actions)
    :f (fn [session] (map #((:f %) session) actions))))

(defmethod combine-actions :transfer/from-local
  [actions]
  (assoc (first actions)
    :f (fn [session] (map #((:f %) session) actions))))

(defn- combine-scope-by-location-and-action-type
  "Combines the bound actions of a scope by location and action-type, producing
  compound actions"
  [action-plan]
  (->>
   action-plan
   (partition-by (juxt :location :action-type))
   (map combine-actions)))

(defn- combine-by-location-and-action-type
  "Combines bound actions by location and action-type, producing compound
  actions"
  [action-plan]
  (walk-action-plan
   identity
   combine-scope-by-location-and-action-type
   #(assoc %1 :args %2)
   action-plan))

;;; augment return
(defmulti augment-return
  "Change the return type of an action, to be an action map with
   :value and :session keys that are the value of the action, and the updated
   session map for the next action.  This creates a consistent return value for
   all action types (effectively creating a monadic value which is a map)."
  (fn [{:keys [action-type] :as action}] action-type))

(defmethod augment-return :default
  [{:keys [f] :as action}]
  (assoc action
    :f (fn [session]
         (assoc action
           :session session
           :value (f session)))))

(defmethod augment-return :fn/clojure
  [{:keys [f] :as action}]
  (assoc action
    :f (fn [session]
         (let [session (f session)]
           (assoc action
             :session session
             :value session)))))

(defn- augment-scope-return-values
  "Augment the return values of each action in a scope."
  [action-plan]
  (map augment-return action-plan))

(defn- augment-return-values
  "Augment the return values of each action."
  [action-plan]
  (walk-action-plan
   identity
   augment-scope-return-values
   #(assoc %1 :args %2)
   action-plan))

;;; translate action plan
(defn translate
  "Process the action-plan, applying groupings and precedence, producing
   an action plan with fully bound functions, ready for execution.

   This is equivalent to using an identity monad with a monadic value
   that is a tree of action maps."
  [action-plan]
  (->
   action-plan
   pop-block ;; pop the default block
   transform-scopes
   transform-executions
   enforce-precedence
   bind-arguments
   combine-by-location-and-action-type
   script-type-scopes
   augment-return-values))


;;; execute action plan
(defn translated?
  "Predicate to test if an action plan has been translated"
  [action-plan]
  (not (and (= 2 (count action-plan))
            (list? (first action-plan))
            (nil? (second action-plan)))))

(defn execute-action
  "Execute a single action"
  [executor [results session] {:keys [f action-type location] :as action}]
  (let [[result session] (executor session f action-type location)]
    [(conj results result) session]))

(defn execute
  "Execute actions by passing the un-evaluated actions to the `executor`
   function (a function with an arglist of [session f action-type location])."
  [action-plan session executor]
  (when-not (translated? action-plan)
    (condition/raise
     :type :pallet/execute-called-on-untranslated-action-plan
     :message "Attempt to execute an action plan that has not been translated"))
  (reduce #(execute-action executor %1 %2) [[] session] action-plan))


;;; Target specific functions
(defn- target-path*
  "Return the vector path of the action plan for the specified phase an
  target-id."
  [phase target-id]
  [:action-plan phase target-id])

(defn target-path
  "Return the vector path of the action plan for the current session target
   node."
  [session]
  {:pre [(keyword? (session/phase session))
         (keyword? (session/target-id session))]}
  (target-path* (session/phase session) (session/target-id session)))

(defn script-template-for-server
  "Return the script template for the specified server."
  [server]
  (let [family (-> server :image :os-family)]
    (filter identity
            [family
             (:packager server)
             (when-let [version (-> server :image :os-version)]
               (keyword (format "%s-%s" (name family) version)))])))

(defn script-template
  "Return the script template for the current group node."
  [session]
  (script-template-for-server (:server session)))

;;; action plan functions based on session

(defn reset-for-target
  "Reset the action plan for the current phase and target node."
  [session]
  {:pre [(:phase session)]}
  (reduce
   #(assoc-in %1 (target-path* %2 (session/target-id session)) nil)
   session
   (phase/all-phases-for-phase (:phase session))))

(defn build-for-target
  "Create the action plan by calling the current phase for the target group."
  [session]
  {:pre [(:phase session)]}
  (let [phase (:phase session)]
    (if-let [f (or
                (phase (-> session :server :phases))
                (phase (:inline-phases session)))]
      (script/with-script-context (script-template session)
        (f (reset-for-target session)))
      session)))

(defn get-for-target
  "Get the action plan for the current phase and target node."
  [session]
  (get-in session (target-path session)))

(defn translate-for-target
  "Build the action plan and translate for the current phase and target node."
  [session]
  {:pre [(:phase session)]}
  (update-in session (target-path session) translate))

(defn execute-for-target
  "Execute the translated action plan for the current target."
  [session executor]
  {:pre [(:phase session)]}
  (script/with-script-context (script-template session)
    (execute
     (get-in session (target-path session)) session executor)))
