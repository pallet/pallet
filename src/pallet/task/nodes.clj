(ns pallet.task.nodes
  "list nodes."
  (:require
   [pallet.compute :as compute]
   [clojure.contrib.pprint :as pprint])
  (:use clojure.contrib.logging))

(defn nodes
  [request]
  (let [ns (compute/nodes-with-details (:compute request))]
    (doseq [n ns]
      (println n))))
