(ns pallet.parameter
  "Provides parameters for use across crates."
  (:refer-clojure :exclude [assoc!])
  (:require
   pallet.arguments
   [clojure.contrib.condition :as condition])
  (:use
   [clojure.contrib.def :only [defunbound]]))

(defonce default-parameters (atom {}))
(defunbound *parameters* "Data that is used across crates or functions.")

(defn reset-defaults
  "Reset default parameters"
  []
  (reset! default-parameters {}))

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

(defn update-default!
  "Update a parameter in the default parameters"
  [[& keys] f]
  (swap! default-parameters update-in keys f))

(defmacro with-parameters
  "Initialise the parameters binding based on the given keys, which are used
   to merge maps from the defaults."
  [keys & body]
  `(binding [*parameters* (reduce merge {} (map @default-parameters ~keys))]
     ~@body))

(defn update!
  "Update a parameter in the bound parameters"
  [[& keys] f]
  (set! *parameters* (update-in *parameters* keys f)))

(defn assoc!
  "Update a parameter in the bound parameters"
  [[& keys] value]
  (set! *parameters* (assoc-in *parameters* keys value)))

(defn get-for
  ([keys]
     (let [result (get-in *parameters* keys ::not-set)]
       (when (= ::not-set result)
         (condition/raise
          :type :parameter-not-found
          :message (format
                    "Could not find keys %s in *parameters*" keys)
          :key-not-set keys))
       result))
  ([keys default]
       (get-in *parameters* keys default)))

(deftype ParameterLookup
  [keys]
  pallet.arguments.DelayedArgument
  (evaluate
   [_]
   (get-for keys)))

(defn lookup
  "Lookup a parameter in a delayed manner. This produces a function, which is
   executed by it's toString method."
  [& keys]
  (ParameterLookup. keys))
