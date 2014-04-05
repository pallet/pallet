(ns pallet.utils.rex-map
  (:require
   [pallet.core.api-builder :refer [defn-sig]]
   [pallet.exception :refer [combine-exceptions]]
   [schema.core :as schema :refer [maybe optional-key validate]]))

;;; # Result Exception Map

;;; In order to assist in consistent reporting of exceptions across
;;; asynchronous go blocks, we can use result exception maps (rex-map)
;;; as the values put into channels.

;;; A rex-map is a map that contains result keys, and an optional
;;; :exception key with a Throwable value.

;;; Other keys are concat'd to merge them.

(def RexMap
  "A result exception map."
  {(optional-key :exception) Throwable
   schema/Keyword [schema/Any]})

(defn-sig merge-rex-maps
  "Merge result exception maps."
  {:sig [[(maybe RexMap) (maybe RexMap) (schema/maybe String) :- RexMap]
         [(maybe RexMap) (maybe RexMap) :- RexMap]]}
  ([m m2 exception-message]
     (let [es (remove nil? [(:exception m) (:exception m2)])
           results (merge-with
                    concat (dissoc m :exception) (dissoc m2 :exception))]
       (cond-> results
               (seq es) (assoc :exception
                          (combine-exceptions es exception-message results)))))
  ([m m2]
     (merge-rex-maps m m2 nil)))
