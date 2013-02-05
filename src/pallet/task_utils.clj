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
