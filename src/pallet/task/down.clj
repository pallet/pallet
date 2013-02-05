(ns pallet.task.down
  "Remove project nodes."
  (:require
   [clojure.pprint :refer [print-table]]
   [clojure.stacktrace :refer [print-cause-trace]]
   [clojure.tools.logging :as logging]
   [pallet.algo.fsmop :refer [complete? failed? wait-for]]
   [pallet.api :as api :refer [print-targets]]
   [pallet.core.api :refer [phase-errors]]
   [pallet.task-utils :refer [pallet-project project-groups]]
   [pallet.utils :refer [apply-map]]))

(defn down
  "Remove project nodes"
  [{:keys [compute project] :as request} & [selector]]
  (let [spec (project-groups (pallet-project project) compute selector)]
    (let [op (apply-map api/converge
                        (map #(assoc % :count 0) spec)
                        (->
                         request
                         (dissoc :config :project)
                         (assoc :environment
                           (or (:environment request)
                               (-> request :project :environment)))))]
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
          (println "See logs for further details"))))))
