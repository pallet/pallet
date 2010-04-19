(ns pallet.resource
  "Resource definition interface."
  (:require pallet.compat)
  (:use [pallet.target
         :only [with-target-template with-target-tag
                *target-tag* *target-template*]]
        [pallet.utils :only [cmd-join *file-transfers*]]
        [pallet.stevedore :only [script]]
        (clojure.contrib core logging
            [def :only [defvar defvar- name-with-attributes]])))

(pallet.compat/require-contrib)

(defvar *required-resources* {} "Resources for each phase")
(defvar *phase* :configure "Execution phase for resources")

(defn reset-resources
  "Reset the list of resources that should be applied to a target"
  [] (set! *required-resources* {}))

(defmacro with-init-resources
  "Invokes ~@body within a context where the required resources are initially
   bound to the value of ~resources."
  [resources & body]
  `(binding [*required-resources* (or ~resources {})]
     ~@body))

(defmacro in-phase
  "Specify the phase for excution of resources."
  [phase & body]
  `(binding [*phase* ~phase]
     ~@body))

(defn after-phase
  "Calculate the name for the after-phase"
  [phase]
  (keyword (str "after-" (name phase))))

(defmacro execute-after-phase
  "Specify the after phase for execution of resources."
  [& body]
  `(binding [*phase* (after-phase *phase*)]
     ~@body))

(defn invoke-resource
  "Registers a resource whose generation is defined by the specified
   invocation function and arguments that will be applied to that fn
   when the associated phase is applied to a node.

   The invocation can be scheduled within one of two 'executions'
   (conceptually, sub-phases):

   :in-sequence - The generated resource will be applied to the node
        \"in order\", as it is defined lexically in the source crate.
        This is the default.
   :aggregated - All aggregated resources are applied to the node
        in the order they are defined, but before all :in-sequence
        resources. Note that all of the arguments to any given
        invocation fn are gathered such that there is only ever one
        invocation of each fn within each phase."
  ([invoke-fn args] (invoke-resource invoke-fn args :in-sequence))
  ([invoke-fn args execution]
    (set! *required-resources*
      (update-in *required-resources*
        [*phase* execution]
        #(conj (or % []) [invoke-fn args])))))

(defn- group-pairs-by-key
  "Transforms a seq of key-value pairs, generally some with identical keys,
   into a seq of pairs (one per unique key in the input seq) where values
   are the concatenation of all of the values of associated with each key
   in the original seq.  Key order from the original seq is retained.

   e.g. (group-pairs-by-key [[:a [1 2]] [:b [3 4]] [:a [5 6]] [:c [7 8]]]) =>
        =>  ([:a ([1 2] [5 6])]
              [:c ([7 8])]
              [:b ([3 4])])"
  [invocations]
  (loop [groups []
         [[invoke-fn] & pairs :as all] invocations]
    (if-not invoke-fn
      (for [invocations groups]
        [(ffirst invocations) (concat (map second invocations))])
      (let [[matching rest] (seq/separate #(= (first %) invoke-fn) all)]
        (recur (conj groups matching) rest)))))

(defmulti invocations->resource-fns
  "Given an execution's invocations, will return a seq of
   functions pre-processed appropriately for that execution."
  (fn [execution invocations] execution))

(defmethod invocations->resource-fns :in-sequence
  [_ invocations]
  (for [[invoke-fn args] (map distinct invocations)]
    (partial apply invoke-fn args)))

(defmethod invocations->resource-fns :aggregated
  [_ invocations]
  (for [[invoke-fn args*] (group-pairs-by-key invocations)]
    (partial invoke-fn args*)))

(defvar- execution-ordering {:aggregated 10, :in-sequence 20})

(defn configured-resources
  "The currently configured resources"
  []
  (into {}
    (for [[phase invocations] *required-resources*]
      [phase (apply concat
               (for [[execution invocations] (sort-by execution-ordering invocations)]
                 (invocations->resource-fns execution invocations)))])))

(defmacro defresource
  "Defines a resource-producing functions.  Takes a name, the
   \"backing function\" that will actually produce the resource, the
   argument signature that the function exposes, and optional arguments.

   Options:

   :execution - the execution that the specified resource will be applied
        within (see 'invoke-resource' for details).  The default is
        :in-sequence"
  [facility & args]
  (let [[facility args] (name-with-attributes facility args)
        [apply-fn args & options] args
        options (apply hash-map options)]
    `(defn ~facility [~@args]
       (invoke-resource
        ~apply-fn
        ~(if (some #{'&} args)
           `(apply vector ~@(filter #(not (= '& %)) args))
           `[~@args])
         ~(:execution options :in-sequence)))))

(defmacro defaggregate
  "Shortcut for defining a resource-producing function with an
   :execution of :aggregate."
  [name & args]
  `(defresource ~name ~@(concat args [:execution :aggregated])))

(defn output-resources
  "Invoke all passed resources."
  [phase resources]
  (when-let [s (seq (resources phase))]
    (cmd-join (map #(%) s))))

(defn phase-list* [phases]
  (lazy-seq
   (when (seq phases)
     (let [phase (first phases)]
       (if (keyword? phase)
         (cons phase
               (cons (after-phase phase)
                     (phase-list* (rest phases))))
         (cons phase
               (cons [(after-phase (first phase)) (second phase)]
                     (phase-list* (rest phases)))))))))

(defn phase-list [phases]
  (phase-list* (or (seq phases) (seq [:configure]))))

(defmacro resource-phases
  "Returns the configured resource map for given resources."
  [& body]
  `(do
     (with-init-resources nil
       ~@body
       (configured-resources))))

(defmacro phase
  "Inline phase definition for use in arguments to the lift method"
  [& body]
  (let [s (keyword (name (gensym "phase")))]
    `(vector ~s (resource-phases (in-phase ~s ~@body)))))

(defmacro defphases
  "Define phases.  A phase is a keyword/[crates] pair.
   Returns a map of phase to (fn [tag template])."
  [& options]
  (let [options (apply hash-map options)]
    `(resource-phases
      ~@(mapcat #(vector `(in-phase ~(first %) ~@(second %))) options))))

(defn produce-phases
  "Binds the target tag and template and outputs the
   resources specified in the body for the given phases."
  [[& phases] tag template phase-map]
  (with-target-template template
    (with-target-tag tag
      (string/join
        ""
        (map (fn [phase]
               (if (keyword? phase)
                 (output-resources phase phase-map)
                 (output-resources (first phase) (second phase))))
          (phase-list phases))))))

(defmacro build-resources
  "Outputs the resources specified in the body for the specified phases.
   This is useful in testing."
  [[& phases] & body]
  `(binding [*file-transfers* {}
             *required-resources* {}]
     (produce-phases
      ~phases *target-tag* *target-template*
      (resource-phases ~@body))))



