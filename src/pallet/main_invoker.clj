(ns pallet.main-invoker
  "Invoke tasks requiring a compute service.  This decouples main from anything
   pallet, jclouds or maven specific, and ensures compiling main doesn't compile
   the world."
  (:require
   [org.jclouds.compute :as jclouds]
   [clojure.contrib.logging :as logging]
   [pallet.compute :as compute]
   [pallet.utils :as utils]
   [pallet.maven :as maven]
   [pallet.main :as main]))

(def default-service-opts (compute/default-jclouds-extensions))

(defn log-info
  []
  (logging/debug (format "OS              %s %s"
                         (System/getProperty "os.name")
                         (System/getProperty "os.version")))
  (logging/debug (format "Arch            %s" (System/getProperty "os.arch")))
  (logging/debug (format "Admin user      %s" (:username utils/*admin-user*)))
  (let [private-key-path (:private-key-path utils/*admin-user*)
        public-key-path (:public-key-path utils/*admin-user*)]
    (logging/debug
     (format "private-key-path %s %s" private-key-path
             (.canRead (java.io.File. private-key-path))))
    (logging/debug
     (format "public-key-path %s %s" public-key-path
             (.canRead (java.io.File. public-key-path))))))

(defn invoke
  [service user key task params]
  (log-info)
  (let [[service user key] (if service
                             [service user key]
                             (maven/credentials))]
    (if service
      (do
        (logging/debug (format "Running as      %s@%s" user service))
        (let [compute (apply jclouds/compute-service
                             (concat [service user key] default-service-opts))]
          (try
            (apply task {:compute compute} params)
            (finally ;; make sure we don't hang on exceptions
             (.. compute getContext close)))))
      (do
        (println "Error: no credentials supplied\n\n")
        (apply (main/resolve-task "help") [])))))
