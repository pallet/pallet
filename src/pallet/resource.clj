(ns pallet.resource
  "Resource definition interface."
  (:require [clojure.contrib.str-utils2 :as string])
  (:use [pallet.target
         :only [with-target-template with-target-tag
                *target-tag* *target-template*]]
        [pallet.utils :only [cmd-join]]
        [pallet.stevedore :only [script]]
        [clojure.contrib.def :only [defvar name-with-attributes]]
        clojure.contrib.logging))

(defvar required-resources (atom {}) "Resources for each phase")
(defvar *phase* :configure "Execution phase for resources")

(defn reset-resources
  "Reset the list of resources that should be applied to a target"
  [] (reset! required-resources {}))

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

(defn add-invocation
  "Add an invocation to the current phase"
  [resources invocation]
  (assoc resources *phase*
         (conj (or (resources *phase*) []) invocation)))

(defn invoke-resource
  "Handle invocation of a resource.  Invocation should add the args to the
  resources configuration args, and add the resource to the required-resources
  as a [invoke-fn arg-var] tuple."
  ([invoke-fn args]
     (swap! required-resources add-invocation [invoke-fn args]))
  ([arg-var invoke-fn args]
     (swap! arg-var conj args)
     (swap! required-resources add-invocation [invoke-fn arg-var])))


(defmacro returning [v & body]
  `(let [return-value# ~v]
     ~@body
     return-value#))

(defn- produce-resource-fn
  "Create a produce funtion for a given resource invoker, binding its arg var
  value.  As a side effect, reset the arg var value."
  [[invoke-fn v]]
  (if (instance? clojure.lang.IDeref v)
    (returning (partial invoke-fn @v)
               (reset! v []))
    (partial apply invoke-fn v)))

(defn configured-resources
  "The currently configured resources"
  []
  (into {}
        (map #(vector
               (first %)
               (map produce-resource-fn (distinct (second %))))
             @required-resources)))

(defmacro defresource
  "defresource is used to define a resource and takes the following arguments:
      [arg-var apply-fn args]

arg-var is a var that will be used to collect the information passed by
multiple invocations of the resource. It should be initialised with (atom []).

apply-fn is a function that will read arg-var and produce a resource.

args is the argument signature for the resource."
  [facility & args]
  (let [[facility args] (name-with-attributes facility args)
        [arg-var apply-fn args] args]
    `(defn ~facility [~@args]
       (invoke-resource
        ~arg-var
        ~apply-fn
        ~(if (some #{'&} args)
           `(apply vector ~@(filter #(not (= '& %)) args))
           `[~@args])))))

(defmacro defcomponent
  "defcomponent is used to define a resource and takes the following arguments:
      [f args]

f is a function that will accept the arguments and produce a resource.
args is the argument signature for the resource, and must end with a variadic element."
  [facility & args]
  (let [[facility args] (name-with-attributes facility args)
        [f args] args]
    `(defn ~facility [~@args]
       (invoke-resource
        ~f
        ~(if (some #{'&} args)
           `(apply vector ~@(filter #(not (= '& %)) args))
           `[~@args])))))

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
     (reset-resources)
     ~@body
     (configured-resources)))

(defmacro phase
  "Inline phase definition for use in arguments to the lift method"
  [& body]
  (let [s (keyword (name (gensym "phase")))]
    `(vector ~s (resource-phases (in-phase ~s ~@body)))))

(defmacro defphases
  "Define phases.  A phase is a keyword/(do) pair.
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
  `(produce-phases
    ~phases *target-tag* *target-template*
    (resource-phases ~@body)))



