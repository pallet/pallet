(ns pallet.task-utils
  "Task helpers that depend on pallet implementation"
  (:require
   [clojure.java.io :refer [file]]
   [clojure.string :as string]
   [clojure.tools.cli :refer [cli]]
   [pallet.compute :refer [service-properties]]
   [pallet.project
    :refer [create-project-file default-pallet-file default-user-pallet-file
            pallet-file-exists?  read-or-create-project read-project
            spec-from-project]]
   [pallet.task :refer [abort report-error]]))

(defn- ns-error-msg [ns crate]
  (str
   "Could not find namespace " ns \newline
   "  This is probably caused by a missing dependency."
   \newline \newline
   "  To solve this, add the correct dependency to your :pallet profile"
   \newline
   "  :dependencies in project.clj."
   (if crate
     (str \newline  \newline
          "  It looks like you are trying to use the " crate " crate."
          \newline
          "  The dependency for this should look like:" \newline \newline
          "    [com.palletops/" crate "-crate \"0.8.0\"]")
     "")
   \newline))

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
        (try
          (read-project pallet-file)
          (catch Exception e
            (if-let [project-file (:project-file (ex-data e))]
              (if-let [[_ path]
                       (re-matches
                        #"Could not locate .* or (.*)\.clj on classpath.*"
                        (.getMessage (.getCause e)))]
                (let [ns (-> path (string/replace "/" ".")
                             (string/replace "_" "-"))
                      [_ crate] (re-matches #"pallet\.crate\.([^.]+)" ns)]
                  (report-error (ns-error-msg ns crate))
                  (throw
                   (ex-info (str "ERROR: Could not find namespace " ns
                                 " while loading " project-file)
                            {:exit-code 1})))
                (throw e))
              (throw e))))
        (abort (str "No pallet configuration for project.  "
                    "Use `lein pallet project-init` to create one")))
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

(defn comma-sep->seq
  [s]
  (and s (.split s ",")))

(defn comma-sep->kw-seq
  [s]
  (and s (map keyword (.split s ","))))

(defn project-groups
  "Compute the groups for a pallet project using the given compute service,
filtered by selectors, groups and roles."
  [pallet-project compute selectors groups roles]
  (let [{:keys [provider]} (service-properties compute)]
    (spec-from-project
     pallet-project
     provider
     (set (comma-sep->kw-seq selectors))
     (set (comma-sep->kw-seq groups))
     (set (comma-sep->kw-seq roles)))))

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
