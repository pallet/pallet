(ns pallet.parameter
  "Provides parameters for use across crates."
  (:require
   pallet.arguments
   [clojure.contrib.condition :as condition])
  (:use
   [clojure.contrib.def :only [defunbound]]))

(defonce default-parameters (atom {}))
(defunbound *parameters* "Data that is used across crates or functions.")

(defn default
  "Set values on the default parameters."
  [& options]
  (doseq [[key value] (apply hash-map options)]
    (swap! default-parameters update-in [:default key] (fn [_] value))))

(defn default-for
  "Set values on the default parameters for a given key"
  [key & options]
  (doseq [[option-key value] (apply hash-map options)]
    (swap! default-parameters update-in [key option-key] (fn [_] value))))


(defmacro with-parameters
  "Initialise the parameters binding based on the given keys."
  [keys & body]
  `(binding [*parameters* (reduce merge {} (map @default-parameters ~keys))]
     ~@body))

(defn update
  "Update a parameter in the bound parameters"
  [[& keys] value]
  (set! *parameters* (update-in *parameters* keys (fn [_] value))))



(deftype ParameterLookup
  [keys]
  pallet.arguments.DelayedArgument
  (evaluate
   [_]
   (let [key (first keys)
         rest-keys (seq (rest keys))
         parameters (get *parameters* key ::not-set)]
     (when (= ::not-set parameters)
       (condition/raise
        :type :parameter-not-found
        :message (format "Could not find key %s in *parameters*" key)
        :key-not-set key))
     (if rest-keys
       (apply parameters rest-keys)
       parameters))))

(defn lookup
  "Lookup a parameter in a delayed manner. This produces a function, which is
   executed by it's toString method."
  [& keys]
  (ParameterLookup. keys))
