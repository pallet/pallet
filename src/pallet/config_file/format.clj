(ns pallet.config-file.format
  "Some standard file formating."
  (:require
   [clojure.string :as string]))

(defn name-values
  "A property file.
   The properties are written \"key value\", one per line.
     m                   a key-value map
     :separator chars    separator to use between key and value
                         (default is a single space)"
  [m & {:keys [separator] :or {separator " "}}]
  (string/join
   (map
    (fn [[key-name value]] (format "%s%s%s\n" (name key-name) separator value))
    m)))

(defn sectioned-properties
  "A sectioned property file.
   This is modeled as a map of maps. The keys of the outer map are the section
   names.  The inner maps are keyword value maps."
  [m & {:keys [separator] :or {separator " = "}}]
  (letfn [(format-section
           [[section-name kv-map]]
           (format
            "[%s]\n%s\n" (name section-name)
                         (name-values kv-map :separator separator)))]
    (string/join (map format-section m))))
