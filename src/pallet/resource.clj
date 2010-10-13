(ns pallet.resource
  "Resources implement the conversion of phase functions to script and other
configuration code."
  (:require
   [pallet.argument :as argument]
   [pallet.utils :as utils]
   [pallet.script :as script]
   [clojure.contrib.seq :as seq]
   [clojure.string :as string]
   [clojure.contrib.logging :as logging])
  (:use
   [clojure.contrib.def :only [defunbound defvar defvar- name-with-attributes]]
   clojure.contrib.core))

(defn pre-phase
  "Calculate the name for the pre-phase"
  [phase]
  (keyword (str "pre-" (name phase))))

(defn after-phase
  "Calculate the name for the after-phase"
  [phase]
  (keyword (str "after-" (name phase))))

(defmacro execute-pre-phase
  "Specify the pre phase for execution of resources."
  [request & body]
  `(let [request# ~request
         phase# (:phase request#)]
     (->
      (assoc request# :phase (pre-phase phase#))
      ~@body
      (assoc :phase phase#))))

(defmacro execute-after-phase
  "Specify the after phase for execution of resources."
  [request & body]
  `(let [request# ~request
         phase# (:phase request#)]
     (->
      (assoc request# :phase (after-phase phase#))
      ~@body
      (assoc :phase phase#))))

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
        invocation of each fn within each phase.
   :collected - All collected resources are applied to the node
        in the order they are defined, but after all :in-sequence
        resources. Note that all of the arguments to any given
        invocation fn are gathered such that there is only ever one
        invocation of each fn within each phase.

   The resource-type determines how the invocation should be handled:

   :script/bash - resource produce bash script for execution on remote machine
   :fn/clojure  - resource is a function for local execution
   :transfer/to-local - resource is a function specifying remote source
        and local destination."
  ([request invoke-fn args]
     (invoke-resource request invoke-fn args :in-sequence :script/bash))
  ([request invoke-fn args execution]
     (invoke-resource request invoke-fn args execution :script/bash))
  ([request invoke-fn args execution resource-type]
     {:pre [(keyword? (:phase request))
            (keyword? (:target-id request))]}
     (let [[execution location] (if (#{:fn/clojure :transfer/to-local}
                                     resource-type)
                                  [:in-sequence :local]
                                  [execution :remote])]
       (update-in
        request
        [:invocations (:phase request) (:target-id request) execution]
        #(conj
          (or % [])
          {:f invoke-fn
           :args args
           :location location
           :type resource-type})))))

(defn- group-by-function
  "Transforms a seq of invocations, generally some with identical :f values
   into a sequence of invocations where the :args are the concatenation of all
   of the :args of associated with each :f in the original seq.  Sequence order
   from the original seq is retained. Keys over than :f and :args are assumed
   identical for a given :f value.

   e.g. (group-by-function
           [{:f :a :args [1 2]}
            {:f :b :args [3 4]}
            {:f :a :args [5 6]}
            {:f :c :args [7 8]]])
        => ({:f :a :args ([1 2] [5 6])}
            {:f :c :args ([7 8])}
            {:f :b :args ([3 4])})"
  [invocations]
  (loop [groups []
         [{:keys [f] :as invocation} & more :as all] invocations]
    (if-not invocation
      (for [invocations groups]
        (assoc (first invocations)
          :args (map :args invocations)))
      (let [[matching rest] (seq/separate #(= (:f %) f) all)]
        (recur (conj groups matching) rest)))))


(defn apply-evaluated
  [f args request]
  (apply f request (map #(when % (argument/evaluate % request)) args)))

(defn apply-aggregated-evaluated
  [f args request]
  (f
   request
   (map
    #(map (fn [x] (when x (argument/evaluate x request))) %)
    args)))

(defmulti invocations->resource-fns
  "Given an execution's invocations, will return a seq of
   functions pre-processed appropriately for that execution."
  (fn [execution invocations] execution))

(defmethod invocations->resource-fns :in-sequence
  [_ invocations]
  (for [{:keys [f args location type]} (distinct invocations)]
    {:location location
     :f (partial apply-evaluated f args)
     :type type}))

(defmethod invocations->resource-fns :aggregated
  [_ invocations]
  (for [{:keys [f args location type]} (group-by-function invocations)]
    {:location location
     :f (partial apply-aggregated-evaluated f args)
     :type type}))

(defmethod invocations->resource-fns :collected
  [_ invocations]
  (for [{:keys [f args location type]} (group-by-function invocations)]
    {:location location
     :f (partial apply-aggregated-evaluated f args)
     :type type}))

(defvar- execution-ordering [:aggregated :in-sequence :collected])

(defn- execution-invocations
  "Sort by execution-ordering"
  [invocations]
  (map #(vector % (% invocations)) execution-ordering))

(defn bound-invocations
  "Configured resources for executions, binding args to methods."
  [invocations]
  (apply
   concat
   (for [[execution invocations] (execution-invocations invocations)]
     (invocations->resource-fns execution invocations))))

(defn arglist-finder [v]
  (or (-?> (:copy-arglist v) resolve meta :arglists first)
      (:use-arglist v)))

(defmacro defresource
  "Defines a resource-producing functions.  Takes a name, a vector specifying
   the symbol to bind the request to and an optional execution phase and backing
   function arguments, the argument signature that the function exposes and the
   body of the resource.

   A \"backing function\" that will actually produce the resource is defined
   with name*."
  [name & args]
  (let [[name args] (name-with-attributes name args)
        [body] args
        apply-fn (first body)       ;(symbol (str (clojure.core/name name) "*"))
        argv (second body)
        execution (::execution (meta name) :in-sequence)
        type (::type (meta name) :script/bash)
        arglist (or (arglist-finder (meta name)) argv)
        ;; remove so not used in an evaluated context
        name (with-meta name
               (dissoc (meta name) :use-arglist :copy-arglist))]
    (assert (pos? (count argv)))        ; mandatory result argument
    `(do
       (defn ~@body)
       (defn ~name
         {:arglists '(~arglist)}
         [& [~@arglist :as argv#]]
         (invoke-resource
          ~(first arglist)
          #'~apply-fn
          (rest argv#)
          ~execution
          ~type)))))

(defmacro deflocal
  "Shortcut for defining a resource-producing function with an
   :execution of :in-sequence. and ::type of :fn/clojure"
  [name & args]
    (let [[name args] (name-with-attributes name args)]
    `(defresource
       ~name
       {::execution :in-sequence ::type :fn/clojure}
       ~@args)))

(defmacro defaggregate
  "Shortcut for defining a resource-producing function with an
    :execution of :aggregate. The option vector specifes the
    backing function arguments."
  [name & args]
  (let [[name args] (name-with-attributes name args)
        attr (merge (or (meta name) {})
                    {::execution :aggregated ::type :script/bash})]
    `(defresource ~name ~attr ~@args)))

(defmacro defcollect
  "Shortcut for defining a resource-producing function with an
    :execution of :collect. The option vector specifes the
    backing function arguments."
  [name & args]
    (let [[name args] (name-with-attributes name args)
          attr (merge (or (meta name) {})
                      {::execution :collected ::type :script/bash})]
    `(defresource ~name ~attr ~@args)))

(deflocal as-local-resource
  "An adaptor for using a normal function as a local resource function"
  (as-local-resource*
   [request f & args]
   (apply f request args)))

(defn script-join
  "Concatenate multiple scripts, removing blank lines"
  [scripts]
  (str
   (string/join \newline
     (filter (complement utils/blank?) (map #(when % (string/trim %)) scripts)))
   \newline))

(defmulti resource-evaluate-fn
  "Create a resource evaluation function that combines the evaluation"
  (fn [type & _] type))

(defn resource-evaluate-transfer-fn
  [type location s]
  (fn [request]
    {:type type
     :location location
     :transfers (map #((:f %) request) s)
     :request request}))

(defmethod resource-evaluate-fn :transfer/from-local
  [type location s]
  (resource-evaluate-transfer-fn type location s))

(defmethod resource-evaluate-fn :transfer/to-local
  [type location s]
  (resource-evaluate-transfer-fn type location s))

(defmethod resource-evaluate-fn :script/bash
  [type location s]
  (fn [request]
    {:type type
     :location location
     :cmds (script-join (map #((:f %) request) s))
     :request request}))

(defmethod resource-evaluate-fn :fn/clojure
  [type location s]
  (fn [request]
    {:type type
     :location location
     :request (reduce #((:f %2) %1) request s)}))

(defn output-resources
  "Build an execution list for the passed resources.  The result is a sequence
   of [location type f] maps, where location is either :local or :remote, and f
   returns a string for remote execution, or is a no argument function for local
   execution."
  [resources]
  {:pre [(or (map? resources) (nil? resources))
         (every?
          #{:in-sequence :aggregated :collected}
          (keys resources))
         (every? vector? (vals resources))]}
  (for [s (partition-by
           (juxt :location :type) (bound-invocations resources))]
    {:location (:location (first s))
     :type (:type (first s))
     :f (resource-evaluate-fn
         (:type (first s)) (:location (first s)) s)}))

(defn produce-phase
  "Produce the :phase phase from the :invocations"
  [request]
  {:pre [(keyword? (:phase request))
         (keyword? (:target-id request))]}
  (let [phase (:phase request)
        target-id (:target-id request)]
    (seq (output-resources
          (-> request :invocations phase target-id)))))

(defmulti execute-resource
  "Execute a resource of the given type.  Returns [request result]"
  (fn [request resource-type & _] resource-type))

(defmethod execute-resource :script/bash
  [request resource-type execute-fn f]
  (script/with-template [(-> request :node-type :image :os-family)
                         (-> request :target-packager)]
    (let [{:keys [cmds request location resource-type]} (f request)]
      [request (execute-fn cmds)])))

(defmethod execute-resource :transfer/to-local
  [request resource-type execute-fn f]
  (script/with-template [(-> request :node-type :image :os-family)
                         (-> request :target-packager)]
    (let [{:keys [transfers request location resource-type]} (f request)]
      [request (execute-fn transfers)])))

(defmethod execute-resource :transfer/from-local
  [request resource-type execute-fn f]
  (script/with-template [(-> request :node-type :image :os-family)
                         (-> request :target-packager)]
    (let [{:keys [transfers request location resource-type]} (f request)]
      [request (execute-fn transfers)])))

(defmethod execute-resource :fn/clojure
  [request resource-type execute-fn f]
  [(:request (or (and execute-fn (execute-fn f request))
                 (f request)))
   nil])

(defn execute-commands
  "Execute commands by passing the evaluated resources to the function of the
   correct type in fn-map."
  [request fn-map]
  (loop [[{:keys [location type f] :as command}
          & rest :as commands] (:commands request)
          request request
          result []]
    (if command
      (let [[request fn-result] (execute-resource request type (type fn-map) f)]
        (recur rest request (if fn-result (conj result fn-result) result)))
      [result request])))

(defn phase-list*
  "Add pre and after phases"
  [phases]
  (lazy-seq
   (when (seq phases)
     (let [phase (first phases)]
       (if (keyword? phase)
         (cons (pre-phase phase)
               (cons phase
                     (cons (after-phase phase)
                           (phase-list* (rest phases)))))
         (cons (pre-phase (first phase))
               (cons phase
                     (cons [(after-phase (first phase)) (second phase)]
                           (phase-list* (rest phases))))))))))

(defn phase-list
  "Add default phases, pre and after phases."
  [phases]
  (phase-list* (or (seq phases) (seq [:configure]))))

(defmacro phase
  "Create a phase function from a sequence of crate invocations with
   an ommited request parameter.

   eg. (phase
         (file \"/some-file\")
         (file \"/other-file\"))

   which generates a function with a request argument, that is thread
   through the function calls. The example is thus equivalent to:

   (fn [request] (-> request
                   (file \"/some-file\")
                   (file \"/other-file\"))) "
  [& body]
  `(fn [request#] (-> request# ~@body)))

(defn produce-phases
  "Join the result of produce-phase, executing local resources.
   Useful for testing."
  [phases request]
  (clojure.contrib.logging/trace
   (format "produce-phases %s %s" phases request))
  (let [execute
        (fn [request]
          (let [commands (produce-phase request)
                [result request] (if commands
                                   (execute-commands
                                    (assoc request :commands commands)
                                    {:script/bash (fn [cmds] cmds)
                                     :transfer/from-local (fn [& _])
                                     :transfer/to-local (fn [& _])})
                                   [nil request])]
            [(string/join "" result) request]))]
    (reduce
     #(let [[result request] (execute (assoc (second %1) :phase %2))]
        [(str (first %1) result) request])
     ["" request]
     (phase-list phases))))

(defmacro build-resources
  "Outputs the remote resources specified in the body for the specified phases.
   This is useful in testing."
  [[& {:as request-map}] & body]
  `(let [f# (phase ~@body)
         request# (or ~request-map {})
         request# (update-in request# [:phase]
                             #(or % :configure))
         request# (update-in request# [:node-type :image :os-family]
                             #(or % :ubuntu))
         request# (update-in request# [:node-type :tag]
                             #(or % :id))
         request# (update-in request# [:target-id]
                             #(or %
                                  (and (:target-node request#)
                                       (keyword
                                        (.getId (:target-node request#))))
                                  :id))
         request# (update-in request# [:all-nodes]
                             #(or % [(:target-node request#)]))
         request# (update-in request# [:target-nodes]
                             #(or % (:all-nodes request#)))
         request# (update-in
                   request# [:target-packager]
                   #(or
                     %
                     (get-in request# [:node-type :image :packager])
                     (let [os-family# (get-in
                                       request#
                                       [:node-type :image :os-family])]
                       (cond
                        (#{:ubuntu :debian :jeos :fedora} os-family#) :aptitude
                        (#{:centos :rhel} os-family#) :yum
                        (#{:arch} os-family#) :pacman
                        (#{:suse} os-family#) :zypper
                        (#{:gentoo} os-family#) :portage))))]
     (script/with-template
       [(-> request# :node-type :image :os-family)
        (-> request# :target-packager)]
       (produce-phases [(:phase request#)] (f# request#)))))
