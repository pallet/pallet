(ns pallet.task.nodes
  "List nodes."
  (:require
   [pallet.compute :as compute]
   [clojure.pprint :as pprint])
  (:use clojure.tools.logging))

(defn nodes
  "List all nodes in the compute service"
  [request]
  (let [ns (compute/nodes (:compute request))]
    (doseq [n ns]
      (println n))))
