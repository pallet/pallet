(ns pallet.core.api-builder
  "Defn forms for api functions"
  (:require
   [clojure.string :refer [join]]
   [clojure.walk :refer [postwalk]]
   [com.palletops.api-builder :refer [def-defn]]
   [com.palletops.api-builder.stage :refer [validate-errors validate-sig]]
   [pallet.exception :refer [domain-error?]]))

;;; # Add sig to doc string
(defn remove-schema-ns
  [expr]
  (postwalk
   (fn [x]
     (if (symbol? x)
       (if-let [n (namespace x)]
         (let [tn ((symbol n) (ns-aliases *ns*))]
           (if (or (and tn (= (ns-name tn) 'schema.core))
                   (= (symbol n) 'schema.core))
             (symbol (name x))
             x))
         x)
       x))
   expr))

(defn format-sig
  [sig]
  (let [n (count sig)]
    (join " " (map remove-schema-ns (assoc-in sig [(- n 2)] "->")))))

(defn format-sigs
  [sigs]
  (str \newline \newline
       "    "
       (join "\n    " (map format-sig sigs))))

(defn add-sig-doc
  "Add :sig the function's doc string."
  []
  (fn add-meta [defn-map]
    (update-in defn-map [:meta :doc]
               str (format-sigs (-> defn-map :meta :sig)))))


;;; # API defn
(def-defn defn-api
  [(validate-errors domain-error?)
   (validate-sig)
   (add-sig-doc)])
