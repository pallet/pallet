(ns pallet.task.up
  "Bring up nodes."
  (:require
   [clojure.pprint :refer [pprint print-table]]
   [clojure.stacktrace :refer [print-cause-trace]]
   [clojure.tools.logging :as logging]
   [pallet.algo.fsmop :refer [complete? failed? wait-for]]
   [pallet.api :as api :refer [print-targets]]
   [pallet.core.api :refer [phase-errors]]
   [pallet.node :refer [node-map]]
   [pallet.task-utils
    :refer [comma-sep->kw-seq pallet-project process-args project-groups]]
   [pallet.utils :refer [apply-map]]))

(def switches
  [["-s" "--selectors" "A comma separated list of selectors"
    :default "default"]
   ["-p" "--phases" "A comma separated list of phases"]
   ["-f" "--format" "Output nodes in a table [table,edn]"
    :default "table"]
   ["-q" "--quiet" "No output on successful completion"
    :flag true :default false]])

(def help
  (str "Bring up project nodes.\n"
       \newline
       "If you have variants defined, you can use `-s` to select variants.\n"
       \newline
       (last (process-args "nodes" nil switches))))

(defn ^{:doc help} up
  [{:keys [compute project quiet format] :as request} & args]
  (let [[{:keys [selectors phases quiet format]} args]
        (process-args "up" args switches)
        spec (project-groups (pallet-project project) compute selectors)]
    (let [op (apply-map api/converge
                        spec
                        (->
                         request
                         (merge (when phases
                                  {:phase (comma-sep->kw-seq phases)}))
                         (dissoc :config :project)
                         (assoc :environment
                           (or (:environment request)
                               (-> request :project :environment)))))]
      (wait-for op)
      (if (complete? op)
        (when-not quiet
          (if (= format "edn")
            (pprint (map node-map (map :node (:targets @op))))
            (print-targets @op)))
        (binding [*out* *err*]
          (println "An error occured")
          (when-let [e (seq (phase-errors op))]
            (print-table (->> e (map :error) (map #(dissoc % :type)))))
          (when-let [e (:exception @op)]
            (print-cause-trace e)
            (throw (ex-info "pallet up failed" {:exit-code 1} e)))
          (throw (ex-info "See logs for further details" {:exit-code 1})))))))
