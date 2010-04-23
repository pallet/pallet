(ns pallet.task.nodes
  "list nodes."
  (:require
   [org.jclouds.compute :as jclouds]))

(defn nodes
  []
  (jclouds/nodes))
