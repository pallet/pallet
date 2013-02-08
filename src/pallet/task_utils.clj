(ns pallet.task-utils
  "Task helpers that depend on pallet implementation"
  (:require
   [clojure.java.io :refer [file]]
   [clojure.tools.cli :refer [cli]]
   [pallet.compute :refer [service-properties]]
   [pallet.project
    :refer [create-project-file default-pallet-file default-user-pallet-file
            pallet-file-exists?  read-or-create-project read-project
            spec-from-project]]
   [pallet.task :refer [abort report-error]]))

(defn pallet-project
  "Load the pallet project for the specified lein project.  Will create
   a user level config if called outside of a project and no user level
   config exists yet."
  [lein-project]
  (let [project-name (:name lein-project)
        pallet-file (or (:pallet-file lein-project)
                        (when (and project-name (:root lein-project))
                          default-pallet-file))]
    (if pallet-file
      (if (pallet-file-exists? pallet-file)
        (read-project pallet-file)
        (abort (str "No pallet configuration for project.  "
                    "Use `lein pallet project-int` to create one")))
      (read-or-create-project "pallet" default-user-pallet-file))))

(defn create-pallet-project
  "Create the pallet project for the specified lein project."
  [lein-project]
  (let [project-name (:name lein-project)
        pallet-file (or (:pallet-file lein-project)
                        (when (and project-name (:root lein-project))
                          default-pallet-file))]
    (if (pallet-file-exists? pallet-file)
      (abort (str "Pallet configuration already exists for project in "
                  pallet-file))
      (do (create-project-file (or project-name "default") pallet-file)
          pallet-file))))

(defn comma-sep->kw-seq
  [s]
  (and s (map keyword (.split s ","))))

(defn project-groups
  "Compute the groups for a pallet project using the given compute service"
  [pallet-project compute selectors]
  (let [{:keys [provider]} (service-properties compute)]
    (spec-from-project
     pallet-project
     provider
     (comma-sep->kw-seq selectors))))

(defn process-args
  "Process command line arguments. Returns an option map, a vector of arguments
  and a help string."
  [task args switches]
  (try
    (apply cli args switches)
    (catch Exception e
      (report-error
       (str (str (.getMessage e) " for '" task "'") \newline \newline
            (last (apply cli nil switches))))
      (throw (ex-info
              (str "Pallet " task " task failed")
              {:exit-code 1}
              e)))))
