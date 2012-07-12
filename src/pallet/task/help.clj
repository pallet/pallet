(ns pallet.task.help
  "Display a list of tasks or help for a given task."
  (:require
   [clojure.string :as string])
  (:use
   [chiba.plugin :only [plugins]]))

(def impl-ns "pallet.task.")
(def task-list (atom nil))

(defn tasks
  "Find the available tasks."
  []
  (try
    (or @task-list (reset! task-list (plugins impl-ns)))
   (catch java.io.FileNotFoundException e
     #{'pallet.task.help})))

(defn- resolve-task
  [task-name]
  (try (let [task-ns (doto (symbol (str impl-ns task-name)) require)
             task (ns-resolve task-ns (symbol task-name))]
         [task-ns task])
       (catch java.io.FileNotFoundException e
         [nil nil])))

(defn- task-docstring
  [task-ns task]
  (let [help-fn (and (not= task-ns 'pallet.task.help)
                     (ns-resolve task-ns 'help))]
    (or
     (and help-fn (help-fn))
     (:doc (meta task))
     (:doc (meta (find-ns task-ns))))))

(defn- task-ns-docstring
  [task-ns]
  (:doc (meta (find-ns task-ns))))

(defn- arglists [task]
  (for [args (or (:help-arglists (meta task))
                 (:arglists (meta task)))]
    (vec (remove #{'request 'session} args))))

(defn- formatted-arglists [task-name arglists]
  (apply str
         (map
          #(str \newline "  lein pallet " task-name " "
                (string/join " " %))
          arglists)))

(defn- first-line [s]
  (first (.split s "\n")))

(defn help-for
  "Help for a task is taken from the result of calling a help function in the
  task namespace if present, or its docstring, or if that's not present the
  docstring in its namespace."
  [task-name]
  (let [[task-ns task] (resolve-task task-name)]
    (if task
      (str (task-docstring task-ns task) \newline
           (formatted-arglists task-name (arglists task)))
      (format "Task: '%s' not found" task-name))))

(defn help-summary-for
  "Use the namespace doc string, or the first line of the task function doc
  string."
  [task-ns]
  (try
    (require task-ns)
    (catch Exception e
      (binding [*out* *err*]
        (str "  Problem loading " task-ns  ": "(.getMessage e)))))
  (let [task-name (last (.split (name task-ns) "\\."))
        doc (or
             (task-ns-docstring task-ns)
             (let [[task-ns task] (resolve-task task-name)]
               (when task
                 (first-line (task-docstring task-ns task)))))]
    (str task-name (apply str (repeat (- 16 (count task-name)) " "))
         " " doc)))

(defn help
  "Display a list of tasks or help for a given task."
  {:no-service-required true}
  ([task] (println (help-for task)))
  ([]
     (println "Pallet is a cloud automation tool.\n")
     (println "Several tasks are available:")
     (doseq [task-ns (tasks)]
       (println (help-summary-for task-ns)))
     (println "\nRun pallet help $TASK for details.")

     (if @task-list
       (do
         (println "\nYou can write project specific tasks under the\n"
                  "pallet.task namespace.")
         (println "\nOptions:")
         (println "  -P           name-of-service (in ~/.pallet/config.clj)")
         (println "")
         (println "  -provider    name-of-cloud-provider")
         (println "  -identity    login for cloud service API")
         (println "  -credential  key or password for cloud service API")
         (println "\nIf no options are given, the following sequence is used to")
         (println "find a service to use.")
         (println "\n  the pallet.config.service property is checked for the")
         (println "    name of a var to use for the service,")
         (println "\n  the ~/.pallet/config.clj is checked for an active profile")
         (println "    specified with `defpallet`.  e.g.")
         (println "      (defpallet")
         (println "        :services {")
         (println "          :aws {:provider \"ec2\"")
         (println "                :identity \"username or key\"")
         (println "                :credential \"password, key or secret key\"}})")
         (println "\n  the ~/.m2/settings.xml is checked for an active profile")
         (println "    with the following properties:")
         (println "      pallet.compute.provider")
         (println "      pallet.compute.identity")
         (println "      pallet.compute.credential,")
         (println "\n  the var pallet.config/service is used if it exists."))
       (do
         (println "Run the `lein new pallet` to create a pallet project.\n")
         (println "http://palletops.com/doc/first-steps/ for details")))
     (println "\nSee http://palletops.com.")))
