(ns pallet.main-invoker
  "Invoke tasks requiring a compute service.  This decouples main from anything
   pallet, jclouds or maven specific, and ensures compiling main doesn't compile
   the world."
  (:require
   [clojure.tools.logging :as logging]
   [pallet.compute :as compute]
   [pallet.configure :as configure]
   [pallet.blobstore :as blobstore]
   [pallet.utils :as utils]
   [pallet.main :as main]))

(defn log-info
  [admin-user]
  (logging/debugf "OS              %s %s"
                   (System/getProperty "os.name")
                   (System/getProperty "os.version"))
  (logging/debugf "Arch            %s" (System/getProperty "os.arch"))
  (logging/debugf "Admin user      %s" (:username admin-user))
  (let [private-key-path (:private-key-path admin-user)
        public-key-path (:public-key-path admin-user)]
    (logging/debugf
     "private-key-path %s %s"
     private-key-path (.canRead (java.io.File. private-key-path)))
    (logging/debugf
     "public-key-path %s %s"
     public-key-path (.canRead (java.io.File. public-key-path)))))

(defn find-admin-user
  "Return the admin user"
  [defaults project profiles]
  (or
   (utils/admin-user-from-config (:pallet project))
   (utils/admin-user-from-config defaults)
   (utils/admin-user-from-config-var)
   utils/*admin-user*))

(defn compute-service-from-config-files
  [defaults project profiles]
  (or
   (compute/compute-service-from-config (:pallet project) profiles)
   (compute/compute-service-from-config defaults profiles)
   (apply compute/compute-service-from-settings profiles)))

(defn find-compute-service
  "Look for a compute service in the following sequence:
     Check pallet.config.service property,
     check maven settings,
     check pallet.config/service var.
   This sequence allows you to specify an overridable default in
   pallet.config/service."
  [options defaults project profiles]
  (or
   (compute/compute-service-from-map options)
   (when (seq profiles)
     (compute-service-from-config-files defaults project profiles))
   (compute/compute-service-from-property)
   (compute/compute-service-from-config-var)
   (compute-service-from-config-files defaults project profiles)))

(defn find-blobstore
  "Look for a compute service in the following sequence:
     Check pallet.config.service property,
     check maven settings,
     check pallet.config/service var.
   This sequence allows you to specify an overridable default in
   pallet.config/service."
  [options defaults project profiles]
  (or
   (blobstore/blobstore-from-map options)
   (blobstore/blobstore-from-config (:pallet project) profiles)
   (blobstore/blobstore-from-config defaults profiles)
   (apply blobstore/blobstore-from-settings profiles)))

(defn invoke
  [options task params]
  (let [default-config (or (:defaults options) (configure/pallet-config))
        admin-user (find-admin-user
                    default-config (:project options) (:profiles options))
        compute (try
                  (find-compute-service
                   options default-config
                   (:project options) (:profiles options))
                  (catch IllegalArgumentException e
                    (let [msg (.getMessage e)]
                      (if (and
                           msg
                           (re-find #"provider .* not configured" msg))
                        (binding [*out* *err*]
                          (println msg)
                          (throw pallet.main/exit-task-exception))
                        (throw e)))))]
    (if compute
      (try
        (let [blobstore (find-blobstore
                         options default-config
                         (:project options) (:profiles options))]
          (try
            (log-info admin-user)
            (apply task {:compute compute
                         :blobstore blobstore
                         :project (:project options)
                         :config default-config
                         :user admin-user
                         :environment (:environment options)} params)
            (finally ;; make sure we don't hang on exceptions
             (when blobstore
               (blobstore/close blobstore)))))
        (finally ;; make sure we don't hang on exceptions
         (compute/close compute)))
      (do
        (println "Error: no credentials supplied\n\n")
        ((main/resolve-task "help"))
        1))))
