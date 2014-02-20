(ns pallet.task.help
  "Display a list of tasks or help for a given task."
  (:require
   [chiba.plugin :refer [plugins]]
   [clojure.string :as string]
   [pallet.main :refer [pallet-args]]))

(def impl-ns "pallet.task.")
(def task-list (atom nil))

(defn tasks
  "Find the available tasks."
  []
  (try
    (or @task-list
        (reset! task-list (plugins impl-ns)))
    (catch java.io.FileNotFoundException e
      #{'pallet.task.help})))

(defn help-for
  "Help for a task is stored in its docstring, or if that's not present
  in its namespace."
  [task-name]
  (let [task-ns (symbol (str "pallet.task." task-name))
        _ (require task-ns)
        task (ns-resolve task-ns (symbol task-name))
        doc (or (:doc (meta task))
                (:doc (meta (find-ns task-ns))))
        arglists (or (:help-arglists (meta task))
                     (:arglists (meta task)))]
    (str doc
         (apply str
                (map
                 #(str \newline "  lein pallet " task-name " "
                       (string/join " " %))
                 arglists)))))

(defn help-summary-for [task-ns]
  (let [task-name (last (.split (name task-ns) "\\."))]
    (try
      (require task-ns)
      (str task-name (apply str (repeat (- 16 (count task-name)) " "))
           " - " (:doc (meta (find-ns task-ns))))
      (catch Exception e
        (str task-name " failed to load: " (.getMessage e))))))

(defn help
  {:no-service-required true}
  ([_ task] (println (help-for task)))
  ([_]
     (println
      (str "Pallet is a provisioning, configuration management and "
           "orchestration tool.\n"))
     (println "Several tasks are available:\n")
     (doseq [task-summary (sort (map help-summary-for (tasks)))]
       (println task-summary))
     (println "\nRun pallet help $TASK for details.\n\n")

     (println (last (pallet-args nil)))

     (if @task-list
       (do
         (println "\nIf no options are given, the following sequence is used to")
         (println "find a compute service to use.")
         (println "\n  the pallet.config.service property is checked for the")
         (println "    name of a var to use for the service,")
         (println "\n  the ~/.pallet/config.clj is checked for an active profile")
         (println "    specified with `defpallet`.  e.g.")
         (println "      (defpallet")
         (println "        :services {")
         (println "          :aws {:provider \"ec2\"")
         (println "                :identity \"username or key\"")
         (println "                :credential \"password, key or secret key\"}})")
         (println "\n  the pallet.config/service is used if it exists.")
         (println "\nYou can write project specific tasks under the\n"
                  "pallet.task namespace.")))
     (println "\nSee http://palletops.com for documentation")))
