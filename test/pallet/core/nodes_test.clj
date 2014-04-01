(ns pallet.core.nodes
  (:require
   [clojure.test :refer :all]
   [pallet.core.nodes :refer :all]
   [pallet.node :as node]))

(deftest localhost-test
  (let [node (localhost {})]
    (is (= "127.0.0.1" (node/primary-ip node)))))
