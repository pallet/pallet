(ns pallet.task.project-init
  "Initialise a project."
  (:require
   [pallet.task-utils :refer [create-pallet-project]]
   [pallet.task :refer [abort]]))

(defn project-init
  "Initialise a project, creating a pallet.clj configuration file."
  [{:keys [compute project] :as request}]
  (if (:root project)
    (println "Your project's pallet configuration is in"
             (create-pallet-project project))
    (abort "You can only initalise within a lein project.")))
