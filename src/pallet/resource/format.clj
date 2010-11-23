(ns pallet.resource.format
  "Some standard file formating."
  (:require
   [clojure.string :as string]))

(defn sectioned-properties
  "A sectioned property file.
   This is modeled as vector of maps. The keys of the outer map are the section
   names.  The inner maps are keyword value maps."
  [m]
  (letfn [(format-kv
           [[key-name value]]
           (format "%s = %s\n" (name key-name) value))
          (format-section
           [[section-name kv-map]]
           (format
            "[%s]\n%s\n" (name section-name)
            (string/join (map format-kv kv-map))))]
    (string/join (map format-section m))))
