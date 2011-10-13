(ns pallet.compute.jclouds-test-utils
  "Test utils for jclouds"
  (:require
   org.jclouds.compute
   pallet.compute))

(def *compute*)

(defn compute
  "Return the current compute service."
  [] *compute*)

(defn compute-service-fixture
  "Use jcloud's stub compute service, or some other if specified"
  ([] (compute-service-fixture ["stub" "" ""]))
  ([[service account key] & options]
     (fn [f]
       (binding [*compute* (apply pallet.compute/compute-service
                                  service account key options)]
         (f)))))

(defn purge-compute-service
  "Remove all nodes from the current compute service"
  []
  (doseq [node (pallet.compute/nodes *compute*)]
    (pallet.compute/destroy-node *compute* node)))

(defn clean-compute-service-fixture
  "Remove all nodes from the compute service"
  [service account key & options]
  (fn [f]
    (purge-compute-service)
    (f)))
