(ns pallet.task.nodes
  "list nodes."
  (:require
   [pallet.compute :as compute]
   [pallet.api :refer [print-nodes]]))

(defn nodes
  [request]
  (print-nodes (compute/nodes (:compute request))))
