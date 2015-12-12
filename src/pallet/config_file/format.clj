(ns pallet.config-file.format
  "Some standard file formating."
  (:require
   [clojure.string :as string]))

(defn name-values
  "A property file.
   The properties are written \"key value\", one per line.
     m                   a key-value map
     :separator chars    separator to use between key and value
                         (default is a single space)
     :value-formatter    function used to format values to strings"
  [m & {:keys [separator value-formatter]
        :or {separator " " value-formatter str}}]
  (string/join
   (map
    (fn [[key-name value]]
      (str (name key-name) separator (value-formatter value) \newline))
    m)))

(defn sectioned-properties
  "A sectioned property file.
   This is modeled as a map of maps. The keys of the outer map are the section
   names.  The inner maps are keyword value maps.

   Options:
     :separator chars    separator to use between key and value
                         (default is a single space)
     :value-formatter    function used to format values to strings"
  [m & {:keys [separator value-formatter]
        :or {separator " = " value-formatter str}}]
  (letfn [(format-section
            [[section-name kv-map]]
            (let [n (name section-name)
                  vs (name-values kv-map
                                  :separator separator
                                  :value-formatter value-formatter)]
              (if (string/blank? n)
                vs
                (format "[%s]\n%s\n" n vs))))]
    (string/join (map format-section m))))
