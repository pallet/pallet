(ns pallet.task-utils
  "Task helpers that depend on pallet implementation"
  (:require
   [clojure.java.io :refer [file]]
   [pallet.compute :refer [service-properties]]
   [pallet.project
    :refer [default-pallet-file read-or-create-project spec-from-project]]))

(defn pallet-project
  "Load or create the pallet project for the specified lein project."
  [lein-project]
  (let [project-name (:name lein-project)
        pallet-file (or (:pallet-file lein-project)
                        (if (and project-name (:root lein-project))
                          default-pallet-file
                          (file (System/getProperty "user.home")
                                ".pallet" "pallet.clj")))]
    (read-or-create-project (or project-name "default") pallet-file)))

(defn project-groups
  "Compute the groups for a pallet project using the given compute service"
  [pallet-project compute selector]
  (let [{:keys [provider]} (service-properties compute)]
    (spec-from-project
     pallet-project provider (or (and selector (keyword selector)) :default))))

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
