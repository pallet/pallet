(ns pallet.core.nodes
  (:require
   [clojure.test :refer :all]
   [pallet.core.node :as node]
   [pallet.core.nodes :refer :all]))

(deftest localhost-test
  (let [node (localhost {})]
    (is (= "127.0.0.1" (node/primary-ip node)))))
