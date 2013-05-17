(ns pallet.task.project-init
  "Initialise a project."
  (:require
   [pallet.task :refer [abort]]
   [pallet.task-utils :refer [create-pallet-project]]))

(defn project-init
  "Initialise a project, creating a pallet.clj configuration file."
  {:no-service-required true
   :help-arglists '[[project-name?]]}
  ([{:keys [project] :as request}]
     (if (:root project)
       (println "Your project's pallet configuration is in"
                (create-pallet-project project))
       (abort "You must supply a project name outside of a lein project.")))
  ([{:keys [project] :as request} project-name]
     (println "Your project's pallet configuration is in"
              (create-pallet-project {:name project-name
                                      :root (System/getProperty "user.dir")}))))
