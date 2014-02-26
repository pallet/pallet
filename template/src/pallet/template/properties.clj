(ns pallet.template.properties
  "A template for writing properties style config files."
  (:require
   [pallet.utils :refer [as-string]]))

(defn property-section [[name settings]]
  (apply
   str
   "[" (as-string name) "]" \newline
   (map #(format "%s = %s\n" (as-string (first %)) (as-string (second %))) settings)))

(defn property-set [p]
  (apply str (map property-section p)))

(defn properties
  "Write a properties file based on the input argument."
  [values]
  (apply str (map property-set values)))
