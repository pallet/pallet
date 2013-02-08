(ns pallet.task.converge
  "Adjust node counts."
  (:require
   [clojure.pprint :refer [print-table]]
   [clojure.stacktrace :refer [print-cause-trace]]
   [clojure.tools.logging :as logging]
   [pallet.algo.fsmop :refer [complete? failed? wait-for]]
   [pallet.api :as api :refer [print-targets]]
   [pallet.core.api :refer [phase-errors]]
   [pallet.task :refer [abort maybe-resolve-symbol-string]]
   [pallet.task-utils :refer [pallet-project project-groups]]))

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
  [{:keys [compute project] :as request} & args]
  (let [[spec & args] (build-args args)
        spec (or spec
                 (project-groups (pallet-project project) compute nil))]
    (logging/debugf "converge %s" (pr-str spec))
    (when-not spec
      (throw (ex-info "Converge with no group specified" {:args args})))
    (let [op (apply api/converge
                    spec
                    (concat
                     args
                     (apply concat
                            (->
                             request
                             (dissoc :config :project)
                             (assoc :environment
                               (or (:environment request)
                                   (-> request :project :environment)))))))]
      (wait-for op)
        (if (complete? op)
          (print-targets @op)
          (do
            (println "An error occured")
            (when-let [e (:exception @op)]
              (print-cause-trace e))
            (when-let [e (seq (phase-errors op))]
              (print-table (->> e (map :error) (map #(dissoc % :type)))))
            (println "See logs for further details"))))))
