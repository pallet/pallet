(ns pallet.contracts-test
  (:require
   [clj-schema.schema :refer [map-schema]]
   [clojure.test :refer :all]
   [pallet.contracts :refer :all]))

(deftest check-keys-test
  (let [schema (map-schema :strict [[:a] number?])]
    (is (thrown? Exception
                 (check-keys {:a 1 :b 2} [:a :b] schema "test")))
    (is (check-keys {:a 1 :b 2}  [:a] schema "test"))))

(deftest schemas-are-loose-test
  (let [input  {:network {:security-group "default"}}
        output (check-node-spec input)]
    (is (= input output))))      

