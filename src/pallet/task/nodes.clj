(ns pallet.task.nodes
  "list nodes."
  (:require
   [pallet.compute :as compute]
   [clojure.contrib.pprint :as pprint])
  (:use clojure.contrib.logging))

(defn nodes
  [request]
  (let [ns (compute/nodes (:compute request))]
    (doseq [n ns]
      (println n))))
