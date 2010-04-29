(ns pallet.task.help
  "Display a list of tasks or help for a given task."
  (:use [clojure.contrib.find-namespaces :only [find-namespaces-on-classpath]]))

(def impl-ns #"^pallet\.task\.")
(def tasks (set (filter #(re-find impl-ns (name %))
                        (find-namespaces-on-classpath))))

(defn help-for
  "Help for a task is stored in its docstring, or if that's not present
  in its namespace."
  [task]
  (let [task-ns (symbol (str "pallet.task." task))
        _ (require task-ns)
        task (ns-resolve task-ns (symbol task))]
    (or (:doc (meta task))
        (:doc (meta (find-ns task-ns))))))

;; affected by clojure ticket #130: bug of AOT'd namespaces losing metadata
(defn help-summary-for [task-ns]
  (require task-ns)
  (let [task-name (last (.split (name task-ns) "\\."))]
    (str task-name (apply str (repeat (- 8 (count task-name)) " "))
         " - " (:doc (meta (find-ns task-ns))))))

(defn help
  {:no-service-required true}
  ([task] (println (help-for task)))
  ([]
     (println "Pallet is a cloud administration tool.\n")
     (println "Several tasks are available:")
     (doseq [task-ns tasks]
       ;; (println (help-summary-for task-ns))
       (println " " (last (.split (name task-ns) "\\."))))
     (println "\nRun pallet help $TASK for details.")

     (println "\nYou can write project specific tasks in a pallet directory.")
     (println "\nOptions:")
     (println "  -service name-of-cloud-service")
     (println "  -user    login for cloud service API")
     (println "  -key     key or password for cloud service API")
     (println "If no options are given, the ~/.m2/settings.xml is checked for")
     (println "an active maven profile with the pallet.service, pallet.user,")
     (println "and pallet.key properties.")
     (println "See http://github.com/hugoduncan/pallet as well.")))
