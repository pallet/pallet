(ns pallet.task.lift
  "Apply configuration."
  (:require
   [clojure.pprint :refer [print-table]]
   [clojure.stacktrace :refer [print-cause-trace]]
   [clojure.tools.logging :as logging]
   [pallet.algo.fsmop :refer [complete? failed? wait-for]]
   [pallet.api :as api :refer [print-targets]]
   [pallet.core.api :refer [phase-errors]]
   [pallet.project :refer [spec-from-project]]
   [pallet.task :refer [abort maybe-resolve-symbol-string]]
   [pallet.task-utils :refer [pallet-project project-groups]]))

(defn- build-args [args]
  (loop [args args
         prefix nil
         m nil
         phases []]
    (if-let [^String a (first args)]
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
      (concat [(set m)] [:phase phases]))))

(defn lift
  "Apply configuration.
     eg. pallet lift mynodes/my-node
   The node-types should be namespace qualified."
  [{:keys [compute project] :as request} & args]
  (let [[spec & args] (build-args args)
        spec (or (seq spec)
                 (project-groups (pallet-project project) compute nil))
        _ (logging/debugf "lift %s" (pr-str spec))
        op (apply api/lift
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
      (binding [*out* *err*]
        (println "An error occured")
        (when-let [e (seq (phase-errors op))]
          (print-table (->> e (map :error) (map #(dissoc % :type)))))
        (when-let [e (:exception @op)]
          (print-cause-trace e)
          (throw (ex-info "pallet up failed" {} e)))
        (println "See logs for further details")))))
