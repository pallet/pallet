(ns pallet.compute.jclouds-test
  (:use clojure.test)
  (:require
   [pallet.compute.jclouds :as jclouds]
   [pallet.compute :as compute])
  (:import [org.jclouds.compute.domain NodeState OsFamily OperatingSystem]))

(deftest compute-node?-test
  (is (not (jclouds/compute-node? 1)))
  (is (jclouds/compute-node? (jclouds/make-node "a")))
  (is (every?
       jclouds/compute-node?
       [(jclouds/make-node "a") (jclouds/make-node "b")])))

(deftest print-method-test
  (is (= "             a\t  null\n\t\t ubuntu Some arch Ubuntu Desc\n\t\t RUNNING\n\t\t public:   private: "
         (with-out-str (print (jclouds/make-node "a"))))))


(deftest running?-test
  (is (not (compute/running?
            (jclouds/make-node "a" :state NodeState/TERMINATED))))
  (is (compute/running?
       (jclouds/make-node "a" :state NodeState/RUNNING))))

(deftest node-os-family-test
  (is (= :ubuntu
         (compute/node-os-family
          (jclouds/make-node
           "t"
           :operating-system (OperatingSystem.
                              OsFamily/UBUNTU
                              "Ubuntu"
                              "Some version"
                              "Some arch"
                              "Desc"
                              true))))))

(deftest make-unmanaged-node-test
  (testing "basic tests"
    (let [n (jclouds/make-unmanaged-node "atag" "localhost")]
      (is n)
      (is (jclouds/running? n))
      (is (jclouds/compute-node? n))
      (is (= "localhost" (compute/primary-ip n)))))
  (testing "with ssh-port specification"
    (is (= 2222
           (compute/ssh-port
            (jclouds/make-unmanaged-node
             "atag" "localhost" :user-metadata {:ssh-port 2222})))))
  (testing "with image specification"
    (is (= :ubuntu
           (compute/node-os-family
            (jclouds/make-unmanaged-node
             "atag" "localhost"
             :image "id"
             :operating-system (OperatingSystem. OsFamily/UBUNTU "Ubuntu"
                                                 "Some version" "Some arch"
                                                 "Desc" true)))))))
