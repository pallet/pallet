(ns pallet.crate.rabbitmq-test
  (:use pallet.crate.rabbitmq)
  (:require
   [pallet.resource :as resource]
   [pallet.resource.remote-file :as remote-file]
   [pallet.test-utils :as test-utils])
  (:use clojure.test))

(deftest erlang-config-test
  (is (= "[{mnesia, [{dump_log_write_threshold, 1000}]},{rabbit, []}]."
         (erlang-config {:mnesia {:dump_log_write_threshold 1000}
                         :rabbit {}})))
  (testing "lazy seq"
    (is (= "[{mnesia, [{dump, [0,1,2]}]}]."
         (erlang-config {:mnesia {:dump (range 3)}})))))

(deftest configure-test
  (let [node (test-utils/make-node "id" :ip "12.3.4.5")]
    (testing "no config file"
      (is (= ""
             (first
              (test-utils/build-resources
               [:target-node node
                :host {:id {:rabbitmq {:options {:node-count 1}}}}]
               (#'pallet.crate.rabbitmq/configure nil nil))))))
    (testing "pass through of config"
      (is (= (first
              (test-utils/build-resources
               []
               (remote-file/remote-file
                "cf"
                :content "[{rabbit, []}]."
                :literal true)))
             (first
              (test-utils/build-resources
               [:target-node node
                :parameters {:host
                             {:id-12-3-4-5 {:rabbitmq {:config-file "cf"}}}}]
               (#'pallet.crate.rabbitmq/configure nil {:rabbit {}}))))))
    (testing "default cluster"
      (is (=
           (first
            (test-utils/build-resources
             []
             (remote-file/remote-file
              "cf"
              :content "[{rabbit, [{cluster_nodes, ['rabbit@id']}]}]."
              :literal true)))
           (first
            (test-utils/build-resources
             [:target-node node
              :parameters {:host
                           {:id-12-3-4-5
                            {:rabbitmq {:config-file "cf"
                                        :options {:node-count 2}}}}}]
             (#'pallet.crate.rabbitmq/configure nil {:rabbit {}}))))))
    (testing "ram cluster"
      (is (=
           (first
            (test-utils/build-resources
             []
             (remote-file/remote-file
              "cf"
              :content "[{rabbit, [{cluster_nodes, ['rabbit@tagnode']}]}]."
              :literal true)))
           (let [tag-node (test-utils/make-node
                           "tagnode" :tag "tag" :private-ip "12.3.4.6")]
             (first
              (test-utils/build-resources
               [:target-node node
                :all-nodes [tag-node node]
                :target-nodes [tag-node node]
                :parameters {:host {:id-12-3-4-5
                                    {:rabbitmq {:config-file "cf"
                                                :options {:node-count 2}}}}}]
               (#'pallet.crate.rabbitmq/configure :tag {:rabbit {}})))))))))

(deftest invocation
  (is (test-utils/build-resources
       [:target-node (test-utils/make-node "id" :private-ips ["12.3.4.5"])]
       (rabbitmq :node-count 2))))
