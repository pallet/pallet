(ns pallet.task.up
  "Bring up nodes."
  (:require
   [clojure.pprint :refer [pprint]]
   [clojure.stacktrace :refer [print-cause-trace]]
   [clojure.string :as string]
   [pallet.algo.fsmop :refer [failed? fail-reason wait-for]]
   [pallet.api :as api]
   [pallet.api :refer [print-targets]]
   [pallet.compute :refer [service-properties]]
   [pallet.core.primitives :refer [phase-errors]]
   [pallet.node :refer [node-map]]
   [pallet.task-utils
    :refer [comma-sep->kw-seq
            comma-sep->seq
            pallet-project
            process-args
            project-groups]]
   [pallet.utils :refer [apply-map]]))

(def switches
  [["-s" "--selectors" "A comma separated list of selectors"
    :default "default"]
   ["-g" "--groups" "A comma separated list of groups"]
   ["-r" "--roles" "A comma separated list of group roles"]
   ["-a" "--phases" "A comma separated list of phases"]
   ["-d" "--dry-run" "Don't run anything, just show matching groups"
    :flag true]
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

(defn phases-with-args
  "Take phase keywords and apply comma separated args to each."
  [phases args]
  (map #(if %2
          (apply vector %1 (comma-sep->seq %2))
          %1)
       phases (concat args (repeat nil))))

(defn ^{:doc help} up
  [{:keys [compute project quiet format] :as request} & args]
  (let [[{:keys [selectors phases roles groups quiet format dry-run]} args]
        (process-args "up" args switches)
        pallet-project (pallet-project project)
        spec (project-groups pallet-project compute selectors groups roles)
        phases (phases-with-args (comma-sep->kw-seq phases) args)]
    (if dry-run
      (let [info (vec (map #(select-keys % [:group-name :roles :image]) spec))]
        (println "Dry run")
        (if (pos? (count info))
          (do
            (println
             (if phases (str "      phases: " (vec phases) \newline) "")
             "  Node Specs:")
            (pprint info))
          (println
           (if (or selectors groups roles)
             (let [provider (:provider (service-properties compute))]
               (println
                "No groups match selection, groups and roles criteria.\n"
                "   Provider:" (name provider) \newline
                "     Groups:" (string/join ", "
                                            (map (comp name :group-name)
                                                 (:groups pallet-project))))
               (println "  Node Specs:")
               (pprint (get-in pallet-project [:provider provider])))
             (println
              "No groups defined in pallet.clj, or no node-specs match :default"
              (if (= selectors "default")
                ""
                (str "  selectors: " selectors \newline))
              (if groups (str "     groups: " groups \newline) "")
              (if roles (str "      roles: " roles \newline) "")))))
        (flush))
      (let [op (apply-map api/converge
                          spec
                          :async true
                          (->
                           request
                           (merge (when (seq phases)
                                    {:phase phases}))
                           (dissoc :config :project)
                           (assoc :environment
                             (assoc
                                 (or (:environment request)
                                     (-> request :project :environment))
                               :project (dissoc
                                         (-> request :project)
                                         :environment)))))]
        (wait-for op)
        (if (failed? op)
          (binding [*out* *err*]
            (println "An error occured")
            (when-let [e (seq (phase-errors op))]
              (pprint (->> e (map :error) (map #(dissoc % :type)))))
            (when-let [e (and (failed? op) (:exception (fail-reason op)))]
              (print-cause-trace e)
              (throw (ex-info "pallet up failed" {:exit-code 1} e)))
            (throw
             (ex-info "See logs for further details" {:exit-code 1})))
          (when-not quiet
            (if (= format "edn")
              (pprint (map node-map (map :node (:targets @op))))
              (print-targets @op))))))))
