(ns pallet.compute.jclouds-test-utils
  "Test utils for jclouds"
  (:require
   org.jclouds.compute))

(defn compute-service-fixture
  "Use jcloud's stub compute service, or some other if specified"
  ([] (compute-service-fixture ["stub" "" ""]))
  ([[service account key] & options]
     (fn [f]
       (org.jclouds.compute/with-compute-service
         [(apply
           org.jclouds.compute/compute-service
           service account key options)]
         (f)))))

(defn purge-compute-service
  "Remove all nodes from the current compute service"
  []
  (doseq [node (org.jclouds.compute/nodes)]
    (org.jclouds.compute/destroy-node (.getId node))))

(defn clean-compute-service-fixture
  "Remove all nodes from the compute service"
  [service account key & options]
  (fn [f]
    (purge-compute-service)
    (f)))
