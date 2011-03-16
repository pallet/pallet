(ns pallet.task.lift
  "Apply configuration."
  (:require
   [pallet.core :as core]
   [clojure.contrib.logging :as logging]))

(defn- build-args [args]
  (loop [args args
         prefix nil
         m nil
         tasks []]
    (if-let [a (first args)]
      (cond
       (and (nil? m) (symbol? a) (nil? (namespace a))) (recur
                                                        (next args)
                                                        (name a)
                                                        m
                                                        tasks)
       (not (keyword? a)) (recur
                           (next args)
                           prefix
                           (conj (or m []) a)
                           tasks)
       :else (recur (next args) prefix m (conj tasks a)))
      (concat (if prefix [prefix] []) [(set m)] [:task tasks]))))

(defn lift
  "Apply configuration.
     eg. pallet lift mynodes/my-node
   The node-types should be namespace qualified."
  [request & args]
  (let [args (build-args args)]
    (apply core/lift
           (concat args
                   (apply concat
                          (->
                           request
                           (dissoc :config :project)
                           (assoc :environment
                             (-> request :project :environment))))))))
