(ns pallet.compute-test
  (:use pallet.compute)
  (require
   [pallet.utils]
   [org.jclouds.compute :as jclouds])
  (:use clojure.test
        pallet.test-utils)
  (:import [org.jclouds.compute.domain NodeState OsFamily OperatingSystem]))

(deftest compute-node?-test
  (is (not (compute-node? 1)))
  (is (compute-node? (make-node "a")))
  (is (every? compute-node? [(make-node "a") (make-node "b")])))

(deftest node-counts-by-tag-test
  (is (= {:a 2}
         (node-counts-by-tag [(make-node "a") (make-node "a")]))))

(deftest running?-test
  (is (not (jclouds/running? (make-node "a" :state NodeState/TERMINATED))))
  (is (jclouds/running? (make-node "a" :state NodeState/RUNNING))))

(deftest print-method-test
  (is (= "             a\t  null\n\t\t ubuntu Some arch Ubuntu Desc\n\t\t RUNNING\n\t\t public:   private: "
         (with-out-str (print (make-node "a"))))))

(deftest node-os-family-test
  (is (= :ubuntu
         (node-os-family
          (make-node
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
    (let [n (make-unmanaged-node "atag" "localhost")]
      (is n)
      (is (jclouds/running? n))
      (is (compute-node? n))
      (is (= "localhost" (primary-ip n)))))
  (testing "with ssh-port specification"
    (is (= 2222
           (ssh-port
            (make-unmanaged-node
             "atag" "localhost" :user-metadata {:ssh-port 2222})))))
  (testing "with image specification"
    (is (= :ubuntu
           (node-os-family
            (make-unmanaged-node
             "atag" "localhost"
             :image "id"
             :operating-system (OperatingSystem. OsFamily/UBUNTU "Ubuntu"
                                                 "Some version" "Some arch"
                                                 "Desc" true)))))))
