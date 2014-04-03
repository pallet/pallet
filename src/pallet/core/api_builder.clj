(ns pallet.core.api-builder
  "Defn forms for api functions"
  (:require
   [clojure.string :refer [join]]
   [clojure.walk :refer [postwalk]]
   [com.palletops.api-builder :refer [def-defn]]
   [com.palletops.api-builder.stage
    :refer [validate-errors validate-sig add-sig-doc]]
   [com.palletops.api-builder.stage.log
    :refer [log-entry log-exit log-scope]]
   [pallet.exception :refer [domain-error?]]))

;;; # API defn
(def-defn defn-api
  [(validate-errors domain-error?)
   (validate-sig)
   (add-sig-doc)
   (log-entry)
   (log-exit)
   (log-scope)])

(defn-api x {:sig [[schema.core/Any :- schema.core/Any]]}
  [x]x)
