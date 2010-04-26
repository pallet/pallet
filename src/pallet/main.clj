(ns pallet.main
  (:gen-class)
  (:require
   [org.jclouds.compute :as jclouds]
   [clojure.contrib.command-line :as command-line]
   pallet.core
   [pallet.utils :as utils]
   [pallet.maven :as maven])
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

(def default-service-opts [:log4j :enterprise :ssh])

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
      (let [symbol-map (reduce map-and-resolve-symbols {} args)
            arg-line (str "[ " (apply str (interpose " " args)) " ]")
            params (read-string arg-line)
            params (clojure.walk/prewalk-replace symbol-map params)]
        (if (@no-service-needed task)
          (apply (resolve-task task) params)
          (let [[service user key] (if service
                                     [service user key]
                                     (maven/credentials))]
            (if service
              (let [compute (apply jclouds/compute-service
                             (concat [service user key] default-service-opts))]
                (jclouds/with-compute-service [compute]
                  (apply (resolve-task task) params)))
              (do
                (println "Error: no credentials supplied\n\n")
                (apply (resolve-task "help")))))))
      ;; In case tests or some other task started any:
      (flush)
      (shutdown-agents)
      (System/exit 0))))
