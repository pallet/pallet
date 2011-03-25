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

   Delayed evaluation of parameters specified as arguments to action functions
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
  ([session keys]
     (let [result (get-in (:parameters session) keys ::not-set)]
       (when (= ::not-set result)
         (condition/raise
          :type :parameter-not-found
          :message (format
                    "Could not find keys %s in session :parameters"
                    (if (sequential? keys) (vec keys) keys))
          :key-not-set keys))
       result))
  ([session keys default]
       (get-in (:parameters session) keys default)))

(defn get-for-target
  "Retrieve the host parameter for the current target at the path specified by
   keys.  When no default value is specified, then raise a :parameter-not-found
   if no parameter is set.

       (get-for-target
         {:parameters {:host {:id1 {:a {:b 1} {:d 2}}}}
          :target-id :id1} [:a :b])
         => 1"
  ([session keys]
     (get-for session (concat [:host (-> session :server :node-id)] keys)))
  ([session keys default]
     (get-for
      session (concat [:host (-> session :server :node-id)] keys) default)))

(defn get-for-service
  "Retrieve the service parameter for the service and path specified by
   keys.  When no default value is specified, then raise a :parameter-not-found
   if no parameter is set.

       (get-for-service
         {:parameters {:service {:proxy {:a {:b 1} {:d 2}}}}} [:proxy :a :b])
         => 1"
  ([session keys]
     (get-for session (concat [:service] keys)))
  ([session keys default]
     (get-for session (concat [:service] keys) default)))

(defn- assoc-for-prefix
  "Set the values in a map at the paths specified with prefix prepended to each
   path.

       (assoc-for-prefix {} :prefix [:a :b] 1 [:a :d] 2)
         => {:prefix {:a {:b 1} {:d 2}}}"
  [session prefix {:as keys-value-pairs}]
  (reduce
   #(assoc-in %1 (concat prefix (first %2)) (second %2))
   session
   keys-value-pairs))

(defn assoc-for
  "Set the :parameters values at the paths specified.

       (assoc-for {} [:a :b] 1 [:a :d] 2)
         => {:parameters {:a {:b 1} {:d 2}}}"
  [session & {:as keys-value-pairs}]
  (assoc-for-prefix session [:parameters] keys-value-pairs))

(defn assoc-for-target
  "Set the host parameter values at the paths specified.

       (assoc-for-target {:target-id :id1} [:a :b] 1 [:a :d] 2)
         => {:parameters {:host {:id1 {:a {:b 1} {:d 2}}}}}"
  [session & {:as keys-value-pairs}]
  (assoc-for-prefix
   session [:parameters :host (-> session :server :node-id)] keys-value-pairs))

(defn assoc-for-service
  "Set the service parameter values at the paths specified.

       (assoc-for-service {} :proxy [:a :b] 1 [:a :d] 2)
         => {:parameters {:srvice {:proxy {:a {:b 1} {:d 2}}}}}"
  [session service & {:as keys-value-pairs}]
  (assoc-for-prefix
   session [:parameters :service service] keys-value-pairs))

(defn- update-for-prefix
  "Update a map at the path given by the prefix and keys.
   The value is set to the value return by calling f with the current
   value and the given args.

       (update-for-prefix {:p {:a {:b 1}}} [:p] [:a :b] + 2)
         => {:p {:a {:b 3}}}"
  ([session prefix keys f args]
  (apply update-in session (concat prefix keys) f args)))

(defn update-for
  "Update parameters at the path given by keys.
   The value is set to the value return by calling f with the current
   value and the given args.

       (update-for {:parameters {:a {:b 1}}} [:a :b] + 2)
         => {:parameters {:a {:b 3}}}"
  ([session keys f & args]
     (update-for-prefix session [:parameters] keys f args)))

(defn update-for-target
  "Update host parameters for the current target at the path given by keys.
   The value is set to the value return by calling f with the current
   value and the given args.

       (update-for-target
          {:parameters {:host {:id1 {:a {:b 1}}}}
           :target-id :id1}
          [:a :b] + 2)
         => {:parameters {:host {:id1 {:a {:b 3}}}}}"
  [session keys f & args]
  (update-for-prefix
   session [:parameters :host (-> session :server :node-id)] keys f args))

(defn update-for-service
  "Update serivce parameters for the pecified service at the path given by keys.
   The value is set to the value return by calling f with the current
   value and the given args.

       (update-for-service
          {:parameters {:service {:proxy {:a {:b 1}}}}}
          [:proxy :a :b] + 2)
         => {:parameters {:service {:proxy {:a {:b 3}}}}}"
  [session keys f & args]
  (update-for-prefix session [:parameters :service] keys f args))

;;; Delayed parameter evaluation
(deftype ParameterLookup
  [keys]
  pallet.argument.DelayedArgument
  (evaluate
   [_ session]
   (get-for session keys)))

(deftype ParameterLookupTarget
  [keys]
  pallet.argument.DelayedArgument
  (evaluate
   [_ session]
   (get-for session (concat [:host (-> session :server :node-id)] keys))))

(defn lookup
  "Lookup a parameter in a delayed manner. Use a call to this function as the
   argument of a action.
   This function produces an object, which causes parameter lookup when it's
   toString method is called.

   See also `pallet.argument`."
  [& keys]
  (ParameterLookup. keys))

(defn lookup-for-target
  "Lookup a parameter for the target in a delayed manner. Use a call to this
   function as the argument of a action.  This function produces an object,
   which causes parameter lookup when it's toString method is called.

   See also `pallet.argument`."
  [& keys]
  (ParameterLookupTarget. keys))

;;; Actions
(action/def-clj-action parameters
  "An action to set parameters"
  [session & {:as keyvector-value-pairs}]
  (assoc session
    :parameters (reduce
                 #(apply assoc-in %1 %2)
                 (:parameters session)
                 keyvector-value-pairs)))
