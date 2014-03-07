(ns pallet.compute.node-list-test
  (:refer-clojure :exclude [sync])
  (:require
   [clojure.test :refer :all]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.compute :as compute]
   [pallet.compute.node-list :as node-list]
   [pallet.core.node :as node]
   [pallet.tag :refer [has-state-flag?]]
   [pallet.utils :refer [tmpfile with-temporary with-temp-file]]
   [pallet.utils.async :refer [sync]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest supported-providers-test
  (is (node-list/supported-providers)))

;; (deftest make-node-test
;;   (let [nl (atom nil)]
;;     (is (= {:id "n" :hostname :t :primary-ip "1.2.3.4" :os-family :ubuntu :os-version "10.2"
;;             :xx "n-1-2-3-4"
;;             :ssh-port 22 :private-ip "4.3.2.1" :terminated? false :running? true
;;             nl {:ram 512} {:host "h"} {:username "u"}}
;;            (node-list/make-node
;;             "n" :t "1.2.3.4" :ubuntu
;;             {:private-ip "4.3.2.1" :is-64bit false
;;              :os-version "10.2" :service nl :hardware {:ram 512}
;;              :proxy {:host "h"} :image-user {:username "u"}})))))

(deftest service-test
  (is (instance?
       pallet.compute.protocols.ComputeService
       (compute/instantiate-provider :node-list :node-list [])))
  (is (instance?
       pallet.compute.node_list.NodeList
       (compute/instantiate-provider :node-list :node-list []))))

(deftest nodes-test
  (let [node {:id "n" :hostname "t"  :primary-ip "1.2.3.4" :os-family :ubuntu}
        node-list (compute/instantiate-provider :node-list :node-list [node])]
    (is (= [(assoc node :compute-service node-list)]
           (sync (compute/nodes node-list))))
    (is (node/validate-node (first (sync (compute/nodes node-list)))))))

(deftest tags-test
  (let [node {:id "n" :hostname "t" :primary-ip "1.2.3.4" :os-family :ubuntu}
        node-list (compute/instantiate-provider :node-list :node-list [node])
        node (first (sync (compute/nodes node-list)))]
    (is (= node-list (node/compute-service node)))
    (is (nil? (node/tag node "some-tag")))
    (is (= ::x (node/tag node "some-tag" ::x)))
    (is (= {"pallet/state" "{:bootstrapped true}"} (node/tags node)))
    (is (thrown? Exception (node/tag! node "tag" "value")))
    (is (not (node/taggable? node)))
    (is (has-state-flag? node :bootstrapped))))

(deftest close-test
  (is (nil? (compute/close
             (compute/instantiate-provider :node-list :node-list [])))))


(deftest node-test
  (is (thrown? Exception (node-list/ip-for-name "unresolvable")))
  ;; (is (node-list/node "localhost" {}))
  ;; (is (node/has-base-name? (node-list/node "localhost" {}) "localhost"))
  )

(deftest read-node-file-test
  (testing "valid node-file"
    (with-temp-file [nf (pr-str {:a ["localhost"]})]
      (is (node-list/read-node-file nf) "map"))
    (with-temp-file [nf (pr-str ["localhost"])]
      (is (node-list/read-node-file nf) "vector")))
  (testing "invalid node-file"
    (with-temp-file [nf "abcdef"]
      (is (thrown? Exception (node-list/read-node-file nf))))
    (with-temp-file [nf (pr-str {:a "localhost"})]
      (is (thrown? Exception (node-list/read-node-file nf))))))
