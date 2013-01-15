(ns pallet.task.converge
  "Adjust node counts."
  (:require
   [pallet.core :as core]
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
          ;; a symbol number pair
          (not (.startsWith a ":"))
          (if v
            (let [n (read-string (fnext args))]
              (when-not (number? n)
                (abort
                 (format
                  "Could not determine number of nodes for %s (%s given)"
                  a (fnext args))))
              (recur (nnext args) prefix (assoc (or m {}) v n) phases))
            (abort
             (str "Could not locate node definition for " a)))
          ;; a phase
          :else (recur (next args) prefix m (conj phases (read-string a)))))
      (concat [m] (if prefix [:prefix prefix] []) [:phase phases]))))

(defn converge
  "Adjust node counts.  Requires a map of node-type, count pairs.
     eg. pallet converge mynodes/my-node 1
   The node-types should be namespace qualified."
  [request & args]
  (let [args (build-args args)]
    (apply core/converge
           (concat args
                   (apply concat
                          (->
                           request
                           (dissoc :config :project)
                           (assoc :environment
                             (or (:environment request)
                                 (-> request :project :environment)))))))))
