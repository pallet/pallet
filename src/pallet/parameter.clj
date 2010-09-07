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
                    "Could not find keys %s in request :parameters" keys)
          :key-not-set keys))
       result))
  ([request keys default]
       (get-in (:parameters request) keys default)))

(defn assoc-for
  [request & {:as keys-value-pairs}]
  (reduce
   #(assoc-in %1 (concat [:parameters] (first %2)) (second %2))
   request
   keys-value-pairs))

(defn update-for
  [request keys f]
  (update-in request (concat [:parameters] keys) f))

(deftype ParameterLookup
  [keys]
  pallet.argument.DelayedArgument
  (evaluate
   [_ request]
   (get-for request keys)))

(defn lookup
  "Lookup a parameter in a delayed manner. This produces a function, which is
   executed by it's toString method."
  [& keys]
  (ParameterLookup. keys))

(resource/deflocal parameters
  "Set parameters"
  (parameters* [request & {:as keyvector-value-pairs}]
   (assoc request
     :parameters (reduce
                  #(apply assoc-in %1 %2)
                  (:parameters request)
                  keyvector-value-pairs))))
