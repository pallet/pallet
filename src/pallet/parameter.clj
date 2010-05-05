(ns pallet.parameter
  "Provides parameters for use across crates."
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
  [[& keys] & body]
  `(binding [*parameters* (reduce merge {} (map @default-parameters '~keys))]
     ~@body))

(defn update
  "Update a parameter in the bound parameters"
  [[& keys] value]
  (set! *parameters* (update-in *parameters* keys (fn [_] value))))
