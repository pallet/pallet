(ns pallet.parameter
  "Provides parameters for use across crates."
  (:require
   [pallet.argument :as argument]
   [pallet.resource :as resource]
   [clojure.contrib.condition :as condition]))

(defn from-map
  "Initialise parameters based on the given keys, which are used to merge maps
   from m."
  [m keys]
  (reduce merge {} (map m keys)))

(defn get-for
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
  ([request keys]
     (get-for
      request (concat [:host (:target-id request)] keys)))
  ([request keys default]
     (get-for
      request (concat [:host (:target-id request)] keys) default)))

(defn- assoc-for-prefix
  [request prefix {:as keys-value-pairs}]
  (reduce
   #(assoc-in
     %1 (concat prefix (first %2)) (second %2))
   request
   keys-value-pairs))

(defn assoc-for
  [request & {:as keys-value-pairs}]
  (assoc-for-prefix
   request [:parameters] keys-value-pairs))

(defn assoc-for-target
  [request & {:as keys-value-pairs}]
  (assoc-for-prefix
   request [:parameters :host (:target-id request)] keys-value-pairs))

(defn assoc-for-service
  [request service & {:as keys-value-pairs}]
  (assoc-for-prefix
   request [:parameters :service service] keys-value-pairs))

(defn- update-for-prefix
  ([request prefix keys f args]
  (apply update-in request (concat prefix keys) f args)))

(defn update-for
  ([request keys f & args]
     (update-for-prefix request [:parameters] keys f args)))

(defn update-for-target
  [request keys f & args]
  (update-for-prefix
   request [:parameters :host (:target-id request)] keys f args))

(defn update-for-service
  [request keys f & args]
  (update-for-prefix request [:parameters :service] keys f args))

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
  "Lookup a parameter in a delayed manner. This produces a function, which is
   executed by it's toString method."
  [& keys]
  (ParameterLookup. keys))

(defn lookup-for-target
  "Lookup a parameter for the target in a delayed manner. This produces a
   function, which is executed by it's toString method."
  [& keys]
  (ParameterLookupTarget. keys))

(resource/deflocal parameters
  "Set parameters"
  (parameters* [request & {:as keyvector-value-pairs}]
   (assoc request
     :parameters (reduce
                  #(apply assoc-in %1 %2)
                  (:parameters request)
                  keyvector-value-pairs))))
