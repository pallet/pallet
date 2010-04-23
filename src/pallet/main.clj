(ns pallet.main
  (:gen-class)
  (:require
   [org.jclouds.compute :as jclouds]
   [clojure.contrib.command-line :as command-line]
   pallet.core)
  (:use clojure.contrib.logging))

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

(def no-service-needed (atom #{"help" "version"}))

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

(defn read-arg
  "Function to build a symbol->value map, requiring namespaces as needed."
  [symbol-map arg]
  (if (.contains arg "/")
    (let [i (.lastIndexOf arg "/")
          ns (.substring arg 0 i)]
      (require (symbol ns))
      (assoc symbol-map (symbol arg) (var-get (find-var (symbol arg)))))
    symbol-map))


(defn -main
  "Command line runner."
  [& args]
  (command-line/with-command-line args
    "Pallet command line"
    [[service "Cloud service name."]
     [user "Cloud user name."]
     [key "Cloud key or password."]
     args]
    (let [[task & args] args
          task (or (aliases task) task "help")]
      (let [symbol-map (reduce read-arg {} args)
            arg-line (str "[ " (apply str (interpose " " args)) " ]")
            params (read-string arg-line)
            params (clojure.walk/prewalk-replace symbol-map params)]
        (if (@no-service-needed task)
          (apply (resolve-task task) params)
          (jclouds/with-compute-service [service user key]
            (apply (resolve-task task) params))))
      ;; In case tests or some other task started any:
      (shutdown-agents))))
