(ns pallet.task.destroy-cluster
  "Destroy nodes for a cluster."
  (:require
   [pallet.core :as core]
   [clojure.tools.logging :as logging]))

(defn- build-args [args]
  (loop [args args
         prefix nil
         m nil
         phases []]
    (if-let [a (first args)]
      (cond
       (and (nil? m) (symbol? a) (nil? (namespace a))) (recur
                                                        (next args)
                                                        (name a)
                                                        m
                                                        phases)
       (not (keyword? a)) (recur (next args) prefix a phases)
       :else (recur (next args) prefix m (conj phases a)))
      (concat [m] (if prefix [:prefix prefix] []) [:phase phases]))))

(defn destroy-cluster
  "Adjust node counts of a cluster.  Requires the name of the cluster.
     eg. pallet converge-cluster org.mynodes/my-cluster
   The cluster name should be namespace qualified."
  [request & args]
  (let [args (build-args args)]
    (apply core/destroy-cluster
           (concat args
                   (apply concat
                          (->
                           request
                           (dissoc :config :project)
                           (assoc :environment
                             (or (:environment request)
                                 (-> request :project :environment)))))))))
