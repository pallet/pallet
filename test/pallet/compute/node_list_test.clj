(ns pallet.compute.node-list-test
  (:require
   [clojure.test :refer :all]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.compute :as compute]
   [pallet.compute.node-list :as node-list]
   [pallet.node :as node]
   [pallet.utils :refer [tmpfile with-temporary with-temp-file]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest supported-providers-test
  (is (node-list/supported-providers)))

(deftest make-node-test
  (let [nl (atom nil)]
    (is (= (pallet.compute.node_list.Node.
            "n" :t "1.2.3.4" :ubuntu "10.2" "n-1-2-3-4" 22 "4.3.2.1" false true
            nl {:ram 512} {:host "h"} {:username "u"})
           (node-list/make-node
            "n" :t "1.2.3.4" :ubuntu :private-ip "4.3.2.1" :is-64bit false
            :os-version "10.2" :service nl :hardware {:ram 512}
            :proxy {:host "h"} :image-user {:username "u"})))))

(deftest service-test
  (is (instance?
       pallet.core.protocols.ComputeService
       (compute/instantiate-provider :node-list :node-list [])))
  (is (instance?
       pallet.compute.node_list.NodeList
       (compute/instantiate-provider :node-list :node-list []))))

(deftest nodes-test
  (let [node (node-list/make-node "n" :t "1.2.3.4" :ubuntu)
        node-list (compute/instantiate-provider :node-list :node-list [node])]
    (is (= [(assoc node :service node-list)] (compute/nodes node-list)))
    (is (instance? pallet.compute.node_list.Node
                   (first (compute/nodes node-list))))))

(deftest tags-test
  (let [node (node-list/make-node "n" :t "1.2.3.4" :ubuntu)
        node-list (compute/instantiate-provider :node-list :node-list [node])
        node (first (compute/nodes node-list))]
    (is (= node-list (node/compute-service node)))
    (is (nil? (node/tag node "some-tag")))
    (is (= ::x (node/tag node "some-tag" ::x)))
    (is (= {:bootstrapped true} (node/tags node)))
    (is (thrown? Exception (node/tag! node "tag" "value")))
    (is (not (node/taggable? node)))
    (is (= true (node/tag node :bootstrapped)))))

(deftest close-test
  (is (nil? (compute/close
             (compute/instantiate-provider :node-list :node-list [])))))

(deftest make-localhost-node-test
  (let [node (node-list/make-localhost-node)]
    (is (= "127.0.0.1" (node/primary-ip node)))))

(deftest node-test
  (is (thrown? Exception (node-list/node "unresolvable")))
  (is (node-list/node "localhost"))
  (is (= :localhost (node/group-name (node-list/node "localhost")))))

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
