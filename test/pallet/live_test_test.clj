(ns pallet.crate.live-test-test
  (:use clojure.test)
  (:require
   [pallet.live-test :as live-test]
   [pallet.core :as core]
   [pallet.resource :as resource]
   [pallet.compute :as compute]))


(def nodes {:repo {:phases {}}})

(deftest live-test-test
  (doseq [os-family [:centos :ubuntu]]
    (live-test/with-nodes
      [compute node-map nodes {:repo {:image {:os-family os-family} :count 1}}]
      (let [node-list (compute/nodes compute)]
        (is (= 1 (count ((group-by compute/tag node-list) "repo"))))))))
