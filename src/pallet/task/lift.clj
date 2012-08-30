(ns pallet.task.lift
  "Apply configuration."
  (:require
   [pallet.api :refer [lift] :rename {lift lift2}]
   [clojure.tools.logging :as logging])
  (:use
   [pallet.task :only [abort maybe-resolve-symbol-string]]))

(defn- build-args [args]
  (loop [args args
         prefix nil
         m nil
         phases []]
    (if-let [a (first args)]
      (let [v (maybe-resolve-symbol-string a)]
        (cond
          ;; non symbol as first arg
          (and (nil? m) (not v)) (recur (next args) a m phases)
          ;; a symbol
          (not (.startsWith a ":"))
          (if v
            (recur (next args) prefix (conj (or m []) v) phases)
            (abort (str "Could not locate node definition for " a)))
          ;; a phase
          :else (recur (next args) prefix m (conj phases (read-string a)))))
      (concat (if prefix [prefix] []) [(set m)] [:phase phases]))))

(defn lift
  "Apply configuration.
     eg. pallet lift mynodes/my-node
   The node-types should be namespace qualified."
  [request & args]
  (let [args (build-args args)]
    (apply lift2
           (concat args
                   (apply concat
                          (->
                           request
                           (dissoc :config :project)
                           (assoc :environment
                             (or (:environment request)
                                 (-> request :project :environment)))))))))
