(ns pallet.main
  (:gen-class)
  (:require
   clojure.walk
   [pallet.command-line :as command-line]
   [clojure.string :as string]))

(defn abort [msg]
  (println msg)
  (System/exit 1))

(defn read-targets
  ([dir]
     (try
      (doseq [file (file-seq dir)]
        (load (.getPath file)))
      (catch java.io.FileNotFoundException _
        (abort "No pallet directory found in the current directory."))))
  ([] (read-targets "pallet")))

(def aliases {"--help" "help" "-h" "help" "-?" "help" "-v" "version"
              "--version" "version"})

(defn resolve-task [task]
  (let [task-ns (symbol (str "pallet.task." task))
        task (symbol task)
        error-fn (fn [& _]
                   (abort
                    (format "%s is not a task. Use \"help\" to list all tasks."
                             task)))]
    (try
     (when-not (find-ns task-ns)
       (require task-ns))
     (or (ns-resolve task-ns task)
         error-fn)
     (catch java.io.FileNotFoundException e
       error-fn))))

(defn parse-as-qualified-symbol
  "Convert the given string into a namespace qualified symbol.
   Returns a vector of ns and symbol"
  [arg]
  {:pre [(string? arg)]}
  (if (.contains arg "/")
    (if-let [sym (symbol arg)]
      [(symbol (namespace sym)) sym])))

(defn map-and-resolve-symbols
  "Function to build a symbol->value map, requiring namespaces as needed."
  [symbol-map arg]
  (if-let [[ns sym] (parse-as-qualified-symbol arg)]
    (do
      (require ns)
      (if-let [v (find-var sym)]
        (assoc symbol-map sym (var-get v))
        symbol-map))
    symbol-map))

(defn profiles
  [profiles-string]
  (when profiles-string
    (string/split profiles-string #",")))

(defn -main
  "Command line runner."
  ([& args]
     (command-line/with-command-line args
       "Pallet command line"
       [[service "Cloud service name."]
        [user "Cloud user name."]
        [key "Cloud key or password."]
        [project-options "Project options (usually picked up from project.clj."]
        [P "profiles to use for key lookup"]
        args]
       (let [[task & args] args
             task (or (aliases task) task "help")
             project-options (read-string project-options)]
         (let [symbol-map (reduce map-and-resolve-symbols {} args)
               arg-line (str "[ " (apply str (interpose " " args)) " ]")
               params (read-string arg-line)
               params (clojure.walk/prewalk-replace symbol-map params)
               task (resolve-task task)]
           (if (get (meta task) :no-service-required nil)
             (apply task params)
             (let [_ (require 'pallet.main-invoker)
                   invoker (find-var 'pallet.main-invoker/invoke)]
               (invoker
                service user key (profiles P) task params project-options))))
         ;; In case tests or some other task started any:
         (flush)
         (when-not (System/getProperty "cake.project")
           (shutdown-agents)
           (System/exit 0)))))
  ([] (apply -main *command-line-args*)))
