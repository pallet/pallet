(ns pallet.main
  (:gen-class)
  (:require
   [pallet.command-line :as command-line]
   [clojure.tools.logging :as logging]
   [clojure.stacktrace :refer [print-cause-trace]]
   [clojure.walk :as walk]
   [clojure.string :as string])
  (:use
   [pallet.task :only [abort]]))

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

(defn profiles
  [profiles-string]
  (when profiles-string
    (string/split profiles-string #",")))

(defn- report-unexpected-exception
  "Check the exception to see if it is the `exit-task-exception`, and if it is
   not, then report the exception."
  [^Throwable e]
  (logging/errorf e "Exception")
  (binding [*out* *err*]
    (print-cause-trace e)))

(defn pallet-task
  "A pallet task.

   Returns an integer exit status suitable for System/exit."
  [args & {:keys [environment]}]
  (command-line/with-command-line args
    "Pallet command line"
    [[provider "Cloud provider name."]
     [identity "Cloud user name or key."]
     [credential "Cloud password or secret."]
     [blobstore-provider "Blobstore provider name."]
     [blobstore-identity "Blobstore user name or key."]
     [blobstore-credential "Blobstore password or secret."]
     [P "Profiles to use for key lookup in config.clj or settings.xml"]
     [project-options "Project options (usually picked up from project.clj)."]
     [defaults "Default options (usually picked up from config.clj)."]
     args]
    (let [[task & args] args
          task (or (aliases task) task "help")
          project-options (when project-options
                            (read-string project-options))
          defaults (when defaults
                     (read-string defaults))
          task (resolve-task task)
          return-value (if (:no-service-required (meta task))
                         (apply task args)
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
                             :profiles (profiles P)
                             :project project-options
                             :defaults defaults
                             :environment environment}
                            task
                            args)))]
      (flush)
      nil)))

(defn -main
  "Command line runner."
  ([& args]
     (try
       (pallet-task args)
       (catch Exception e
         (when-let [exit-code (:exit-code (ex-data e))]
           (System/exit exit-code))
         (report-unexpected-exception e)
         (System/exit 1))
       (finally
         (shutdown-agents)))
     (System/exit 0))
  ([] (apply -main *command-line-args*)))
