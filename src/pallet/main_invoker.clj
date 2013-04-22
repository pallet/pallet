(ns pallet.main-invoker
  "Invoke tasks requiring a compute service.  This decouples main from anything
   pallet, jclouds or maven specific, and ensures compiling main doesn't compile
   the world."
  (:use
   [pallet.core.user :only [*admin-user*]])
  (:require
   [bultitude.core :refer [classpath-files]]
   [clojure.tools.logging :as logging]
   [pallet.api :refer [version]]
   [pallet.blobstore :as blobstore]
   [pallet.compute :as compute]
   [pallet.configure :as configure :refer [default-compute-service]]
   [pallet.environment :as environment]
   [pallet.utils :as utils]
   [pallet.main :as main :refer [transient-services]]))

(defn log-info
  [admin-user]
  (logging/debugf "Pallet version: %s" (version))
  (logging/debugf "OS              %s %s"
                   (System/getProperty "os.name")
                   (System/getProperty "os.version"))
  (logging/debugf "Arch            %s" (System/getProperty "os.arch"))
  (logging/debugf "Admin user      %s" (:username admin-user))
  (let [^String private-key-path (:private-key-path admin-user)
        ^String public-key-path (:public-key-path admin-user)]
    (logging/debugf
     "private-key-path %s %s"
     private-key-path (if private-key-path (.canRead (java.io.File. private-key-path)) ""))
    (logging/debugf
     "public-key-path %s %s"
     public-key-path (if public-key-path (.canRead (java.io.File. public-key-path)) ""))
    (doseq [^java.io.File f (classpath-files)]
      (logging/debugf "classpath: %s" (.getPath f)))
    (doseq [[k v] (System/getProperties)]
      (logging/debugf "property: %s %s" k v))))

(defn find-admin-user
  "Return the admin user"
  [defaults project profile]
  (or
   (configure/admin-user-from-config (:pallet project))
   (configure/admin-user-from-config defaults)
   (configure/admin-user-from-config-var)
   *admin-user*))

(defn compute-service-from-config-files
  [defaults project profile]
  (or
   (configure/compute-service-from-config (:pallet project) profile {})
   (configure/compute-service-from-config defaults profile {})))

(defn find-compute-service
  "Look for a compute service in the following sequence:
     Check pallet.config.service property
     check maven settings
     check pallet.config/service var.
   This sequence allows you to specify an overridable default in
   pallet.config/service."
  [options defaults project profile]
  (or
   (configure/compute-service-from-map options)
   (when profile
     (compute-service-from-config-files defaults project profile))
   (configure/compute-service-from-property)
   (configure/compute-service-from-config-var)
   (compute-service-from-config-files
    defaults project (default-compute-service defaults))
   (compute-service-from-config-files
    @transient-services project
    (default-compute-service @transient-services))))

(defn find-blobstore
  "Look for a compute service in the following sequence:
     Check pallet.config.service property
     check maven settings
     check pallet.config/service var.
   This sequence allows you to specify an overridable default in
   pallet.config/service."
  [options defaults project profile]
  (or
   (configure/blobstore-from-map options)
   (configure/blobstore-from-config (:pallet project) profile {})
   (configure/blobstore-from-config defaults profile {})))

(defn invoke
  [options task params]
  (let [default-config (or (:defaults options) (configure/pallet-config))
        admin-user (find-admin-user
                    default-config (:project options) (:service options))
        compute (try
                  (find-compute-service
                   options default-config
                   (:project options) (:service options))
                  (catch IllegalArgumentException e
                    (let [msg (.getMessage e)]
                      (if (and
                           msg
                           (re-find #"provider .* not configured" msg))
                        (binding [*out* *err*]
                          (println msg)
                          (throw (ex-info msg {:exit-code 1})))
                        (throw e)))))]
    (if compute
      (try
        (let [blobstore (find-blobstore
                         options default-config
                         (:project options) (:service options))]
          (try
            (log-info admin-user)
            (apply task
                   {:compute compute
                    :blobstore blobstore
                    :project (:project options)
                    :config default-config
                    :user admin-user
                    :environment
                    (pallet.environment/merge-environments
                     (:environment options)
                     (environment/environment compute))}
                   params)
            (finally ;; make sure we don't hang on exceptions
             (when blobstore
               (blobstore/close blobstore)))))
        (finally ;; make sure we don't hang on exceptions
         (compute/close compute)))
      (do
        (println "Error: no credentials supplied\n\n")
        ((main/resolve-task "help"))
        (throw (ex-info "Error: no credentials supplied" {:exit-code 1}))))))

(defn invoke-no-service
  [options task task-name params]
  (try
    (apply task options params)
    (catch clojure.lang.ArityException e
      (println (.getMessage e))
      (println "Actual arguments" "_options_" (pr-str params))
      (require 'pallet.task.help)
      (let [help (ns-resolve 'pallet.task.help 'help)]
        (help nil task-name)))))
