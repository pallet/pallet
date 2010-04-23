(ns pallet.task.nodes
  "list nodes."
  (:require
   [org.jclouds.compute :as jclouds]
   [clojure.contrib.pprint :as pprint])
  (:use clojure.contrib.logging))

(defn nodes
  []
  (let [ns (jclouds/nodes)]
    (doseq [n ns]
      (pprint/pprint n))))
