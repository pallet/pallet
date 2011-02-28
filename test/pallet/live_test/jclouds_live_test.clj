(ns pallet.live-test.jclouds-live-test
  (:use clojure.test)
  (:require
   [pallet.live-test :as live-test]
   [pallet.core :as core]
   [pallet.resource :as resource]
   [pallet.test-utils :as test-utils]
   [pallet.compute :as compute]))

(use-fixtures :once (test-utils/console-logging-threshold))

(deftest node-types-test
  (is (= {:repo {:group-name :repo :base-group-name :repo
                 :image {:os-family :ubuntu}
                 :count 1 :phases {}}}
         (live-test/node-types
          {:repo {:image {:os-family :ubuntu}
                  :count 1
                  :phases {}}}))))

(deftest counts-test
  (let [specs {:repo {:image {:os-family :ubuntu}
                  :count 1
                  :phases {}}}]
    (is (= {{:group-name :repo :base-group-name :repo
             :image {:os-family :ubuntu}
             :count 1 :phases {}} 1}
           (#'live-test/counts specs)))))

(deftest build-nodes-test
  (let [specs {:repo {:image {:os-family :ubuntu}
                      :count 1
                      :phases {}}}
        service (compute/compute-service "stub" "" "")]
    (is (= 1
           (count
            (live-test/build-nodes
             service (live-test/node-types specs) specs))))))

(deftest live-test-test
  (live-test/set-service! (compute/compute-service "stub" "" ""))
  (live-test/with-live-tests
    (doseq [os-family [:centos]]
      (live-test/test-nodes
       [compute node-map node-types]
       {:repo {:image {:os-family os-family}
               :count 1
               :phases {}}}
       (let [node-list (compute/nodes compute)]
         (is (= 1 (count ((group-by compute/group-name node-list) "repo")))))))
    ;; (is (= 0
    ;;        (count
    ;;         ((group-by compute/group-name (compute/nodes @live-test/service))
    ;;          "repo"))))
    )
  (testing "with prefix"
    (live-test/with-live-tests
      (doseq [os-family [:centos]]
        (live-test/test-nodes
         [compute node-map node-types]
         {:repo {:image {:os-family os-family :prefix "1"}
                 :count 1
                 :phases {}}}
         (let [node-list (compute/nodes compute)]
           (is (= 1
                  (count ((group-by compute/group-name node-list) "repo1")))))))
      ;; (is (= 0
      ;;        (count
      ;;         ((group-by
      ;;            compute/group-name (compute/nodes @live-test/service))
      ;;          "repo1"))))
      )))
