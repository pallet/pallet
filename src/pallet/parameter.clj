(ns pallet.parameter
  "Provides functions for working with parameters.

   Parameters are data maps that allow propogation of information between the
   functions of a crate, and between crates. There are two conventions for using
   parameters in crates that are directly supported here.

   Host specific parameters are specified under
       [:parameters :host (keyword target-id)]
   These functions are `get-for-target`, `assoc-for-target`, and
   `update-for-target`.

   Service specific paramters, used across hosts, are specified under
      [:parameters :service (keyword service-name)]
   These functions are `get-for-service`, `assoc-for-service`, and
   `update-for-service`.

   The `get-for` functions have slightly different semantics compared with
   clojure.core/get, in that they throw an exception if the key is undefined
   and no default value is specified.

   Delayed evaluation of parameters specified as arguments to resource functions
   are also implemented here. `lookup` and `lookup-for-target`.
"
  (:require
   [pallet.action :as action]
   [pallet.argument :as argument]
   [clojure.contrib.condition :as condition]))

(defn from-map
  "Initialise parameters based on the given keys, which are used to merge maps
   from m."
  [m keys]
  (reduce merge {} (map m keys)))

(defn get-for
  "Retrieve the parameter at the path specified by keys.
   When no default value is specified, then raise a :parameter-not-found if no
   parameter is set.

       (get-for {:p {:a {:b 1} {:d 2}}} [:p :a :d])
         => 2"
  ([request keys]
     (let [result (get-in (:parameters request) keys ::not-set)]
       (when (= ::not-set result)
         (condition/raise
          :type :parameter-not-found
          :message (format
                    "Could not find keys %s in request :parameters"
                    (if (sequential? keys) (vec keys) keys))
          :key-not-set keys))
       result))
  ([request keys default]
       (get-in (:parameters request) keys default)))

(defn get-for-target
  "Retrieve the host parameter for the current target at the path specified by
   keys.  When no default value is specified, then raise a :parameter-not-found
   if no parameter is set.

       (get-for-target
         {:parameters {:host {:id1 {:a {:b 1} {:d 2}}}}
          :target-id :id1} [:a :b])
         => 1"
  ([request keys]
     (get-for request (concat [:host (:target-id request)] keys)))
  ([request keys default]
     (get-for request (concat [:host (:target-id request)] keys) default)))

(defn get-for-service
  "Retrieve the service parameter for the service and path specified by
   keys.  When no default value is specified, then raise a :parameter-not-found
   if no parameter is set.

       (get-for-service
         {:parameters {:service {:proxy {:a {:b 1} {:d 2}}}}} [:proxy :a :b])
         => 1"
  ([request keys]
     (get-for request (concat [:service] keys)))
  ([request keys default]
     (get-for request (concat [:service] keys) default)))

(defn- assoc-for-prefix
  "Set the values in a map at the paths specified with prefix prepended to each
   path.

       (assoc-for-prefix {} :prefix [:a :b] 1 [:a :d] 2)
         => {:prefix {:a {:b 1} {:d 2}}}"
  [request prefix {:as keys-value-pairs}]
  (reduce
   #(assoc-in %1 (concat prefix (first %2)) (second %2))
   request
   keys-value-pairs))

(defn assoc-for
  "Set the :parameters values at the paths specified.

       (assoc-for {} [:a :b] 1 [:a :d] 2)
         => {:parameters {:a {:b 1} {:d 2}}}"
  [request & {:as keys-value-pairs}]
  (assoc-for-prefix request [:parameters] keys-value-pairs))

(defn assoc-for-target
  "Set the host parameter values at the paths specified.

       (assoc-for-target {:target-id :id1} [:a :b] 1 [:a :d] 2)
         => {:parameters {:host {:id1 {:a {:b 1} {:d 2}}}}}"
  [request & {:as keys-value-pairs}]
  (assoc-for-prefix
   request [:parameters :host (:target-id request)] keys-value-pairs))

(defn assoc-for-service
  "Set the service parameter values at the paths specified.

       (assoc-for-service {} :proxy [:a :b] 1 [:a :d] 2)
         => {:parameters {:srvice {:proxy {:a {:b 1} {:d 2}}}}}"
  [request service & {:as keys-value-pairs}]
  (assoc-for-prefix
   request [:parameters :service service] keys-value-pairs))

(defn- update-for-prefix
  "Update a map at the path given by the prefix and keys.
   The value is set to the value return by calling f with the current
   value and the given args.

       (update-for-prefix {:p {:a {:b 1}}} [:p] [:a :b] + 2)
         => {:p {:a {:b 3}}}"
  ([request prefix keys f args]
  (apply update-in request (concat prefix keys) f args)))

(defn update-for
  "Update parameters at the path given by keys.
   The value is set to the value return by calling f with the current
   value and the given args.

       (update-for {:parameters {:a {:b 1}}} [:a :b] + 2)
         => {:parameters {:a {:b 3}}}"
  ([request keys f & args]
     (update-for-prefix request [:parameters] keys f args)))

(defn update-for-target
  "Update host parameters for the current target at the path given by keys.
   The value is set to the value return by calling f with the current
   value and the given args.

       (update-for-target
          {:parameters {:host {:id1 {:a {:b 1}}}}
           :target-id :id1}
          [:a :b] + 2)
         => {:parameters {:host {:id1 {:a {:b 3}}}}}"
  [request keys f & args]
  (update-for-prefix
   request [:parameters :host (:target-id request)] keys f args))

(defn update-for-service
  "Update serivce parameters for the pecified service at the path given by keys.
   The value is set to the value return by calling f with the current
   value and the given args.

       (update-for-service
          {:parameters {:service {:proxy {:a {:b 1}}}}}
          [:proxy :a :b] + 2)
         => {:parameters {:service {:proxy {:a {:b 3}}}}}"
  [request keys f & args]
  (update-for-prefix request [:parameters :service] keys f args))

;;; Delayed parameter evaluation
(deftype ParameterLookup
  [keys]
  pallet.argument.DelayedArgument
  (evaluate
   [_ request]
   (get-for request keys)))

(deftype ParameterLookupTarget
  [keys]
  pallet.argument.DelayedArgument
  (evaluate
   [_ request]
   (get-for request (concat [:host (:target-id request)] keys))))

(defn lookup
  "Lookup a parameter in a delayed manner. Use a call to this function as the
   argument of a resource.
   This function produces an object, which causes parameter lookup when it's
   toString method is called.

   See also `pallet.argument`."
  [& keys]
  (ParameterLookup. keys))

(defn lookup-for-target
  "Lookup a parameter for the target in a delayed manner. Use a call to this
   function as the argument of a resource.  This function produces an object,
   which causes parameter lookup when it's toString method is called.

   See also `pallet.argument`."
  [& keys]
  (ParameterLookupTarget. keys))

;;; Resources
(action/def-clj-action parameters
  "A resource to set parameters"
  [request & {:as keyvector-value-pairs}]
  (assoc request
    :parameters (reduce
                 #(apply assoc-in %1 %2)
                 (:parameters request)
                 keyvector-value-pairs)))
