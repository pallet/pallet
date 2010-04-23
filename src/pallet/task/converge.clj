(ns pallet.task.converge
  "Adjust node counts."
  (:require [pallet.core :as core]))

(defn converge
  "Adjust node counts.  Requires a map of node-type, count pairs.
     eg. pallet converge { mynodes/my-node 1 }
   The node-types should be namespace qualified."
  [& args]
  (apply core/converge args))
