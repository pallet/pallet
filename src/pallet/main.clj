(ns pallet.main
  (:require
   [clojure.stacktrace :refer [print-cause-trace]]
   [clojure.tools.cli :refer [cli]]
   [clojure.tools.logging :as logging]
   [pallet.task :refer [abort exit report-error]])
  (:gen-class))

(defn read-targets
  ([dir]
     (try
      (doseq [^java.io.File file (file-seq dir)]
        (load (.getPath file)))
      (catch java.io.FileNotFoundException _
        (abort "No pallet directory found in the current directory."))))
  ([] (read-targets "pallet")))

(def aliases {"--help" "help" "-h" "help" "-?" "help" "-v" "version"
              "--version" "version"})

(defn resolve-task [task]
  (let [task-ns (symbol (str "pallet.task." task))
        task (symbol task)
        error-fn (with-meta
                   (fn [& _]
                     (abort
                      (format
                       "%s is not a task. Use \"help\" to list all tasks."
                       task)))
                   {:no-service-required true})]
    (try
      (when-not (find-ns task-ns)
        (require task-ns))
      (or (ns-resolve task-ns task)
          error-fn)
      (catch java.io.FileNotFoundException e
        error-fn))))

(defn- report-unexpected-exception
  "Check the exception to see if it is the `exit-task-exception`, and if it is
   not, then report the exception."
  [^Throwable e]
  (logging/errorf e "Exception")
  (binding [*out* *err*]
    (print-cause-trace e)))

(def pallet-switches
  [["-P" "--service" "Service key to use (use add-service to create a service"]
   ["-p" "--provider" "Cloud provider name."]
   ["-i" "--identity" "Cloud user name or key."]
   ["-c" "--credential" "Cloud password or secret."]
   ["-B" "--blobstore-provider" "Blobstore provider name."]
   ["-I" "--blobstore-identity" "Blobstore user name or key."]
   ["-C" "--blobstore-credential" "Blobstore password or secret."]
   ["-O" "--project-options" "Project options (usually picked up from project.clj)."]
   ["-D" "--defaults" "Default options (usually picked up from config.clj)."]])

(defn pallet-args
  "Process command line arguments. Returns an option map, a vector of arguments
  and a help string.  Optionally accepts a sequence of switch descriptions."
  ([args switches]
     (apply cli args switches))
  ([args]
     (pallet-args args pallet-switches)))

(def help
  (str "A command line for pallet."
       \newline \newline
       (last (pallet-args nil))))

;;; We use cli in the tasks to process switches, so we need to allow arbitray
;;; switches to pass to the tasks.  We do this by recursively add switches
;;; that fail, and returning these as extra switches, for propagation to the
;;; task.
(def ^:private pallet-option-names
  (map (comp :name #'clojure.tools.cli/generate-spec) pallet-switches))

(defn process-arg-attempt [args extra-switches]
  (try
    (let [[options args] (pallet-args
                          args (concat pallet-switches extra-switches))]
      {:options (select-keys options pallet-option-names)
       :extra (apply dissoc options pallet-option-names)
       :args args
       :extra-switches extra-switches})
    (catch Exception e
      (if-let [[_ switch] (re-matches
                           #"'(.*)' is not a valid argument"
                           (.getMessage e))]
        {:add-switch switch
         :extra-switches extra-switches}
        (throw e)))))

(defn process-args
  "Process arguments, returning options, arguments and unrecognised options."
  [all-args]
  (loop [{:keys [options extra args extra-switches add-switch] :as parsed}
         (process-arg-attempt all-args nil)]
    (if add-switch
      (recur (process-arg-attempt all-args (conj extra-switches [add-switch])))
      [options args extra])))

(defn args-with-extras
  "Add extra switches back into an argument vector."
  [args extras]
  (letfn [(option-to-args [[switch value]]
            (let [k (if (= 1 (count (name switch)))
                      (str "-" (name switch))
                      (str "--" (name switch)))]
              (if (or (= false value) (= true value) (nil? value))
                [k]
                [k value])))]
    (concat (mapcat option-to-args extras) args)))

(defn ^{:doc help} pallet-task
  [args & {:keys [environment]}]
  (let [[{:keys [provider identity credential blobstore-provider
                 blobstore-identity blobstore-credential service
                 project-options defaults] :as options}
         args
         extras]
        (process-args args)]
    (logging/debugf "pallet-task options %s" options)
    (try
      (let [[task-name & args] args
            task-name (or (aliases task-name) task-name "help")
            project-options (when project-options
                              (read-string project-options))
            defaults (when defaults
                       (read-string defaults))
            task (resolve-task task-name)
            return-value (if (:no-service-required (meta task))
                           (let [_ (require 'pallet.main-invoker)
                                 invoker (find-var
                                          'pallet.main-invoker/invoke-no-service)]

                             (invoker
                              {:project project-options
                               :defaults defaults
                               :environment environment}
                              task
                              task-name
                              (args-with-extras args extras)))
                           (let [_ (require 'pallet.main-invoker)
                                 invoker (find-var
                                          'pallet.main-invoker/invoke)]
                             (invoker
                              {:provider provider
                               :identity identity
                               :credential credential
                               :blobstore-provider blobstore-provider
                               :blobstore-identity blobstore-identity
                               :blobstore-credential blobstore-credential
                               :service service
                               :project project-options
                               :defaults defaults
                               :environment environment}
                              task
                              (args-with-extras args extras))))]
        (flush)
        nil)
      (catch Exception e
        ;; suppress exception traces for errors with :exit-code
        (if-let [exit-code (:exit-code (ex-data e))]
          (do (report-error (.getMessage e))
              (exit exit-code))
          (throw e))))))

(defn -main
  "Command line runner."
  ([& args]
     (try
       (pallet-task args)
       (catch Exception e
         (when-let [exit-code (:exit-code (ex-data e))]
           (exit exit-code))
         (report-unexpected-exception e)
         (exit 1))
       (finally
         (shutdown-agents)))
     (exit 0))
  ([] (apply -main *command-line-args*)))

;;; Allow the task to define pallet services
(def transient-services (atom {}))

(defn add-service [name-kw properties]
  (swap! transient-services assoc-in [:services name-kw] properties)
  (when-not (:default-service @transient-services)
    (swap! transient-services assoc :default-service name-kw)))
