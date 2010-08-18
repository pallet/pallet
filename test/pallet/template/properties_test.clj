(ns pallet.template.properties-test
  (:use pallet.template.properties)
  (:use clojure.test))


(deftest properties-test
  (is (= "[xxx]\nk1 = v1\nk2 = v2\n[aaa]\nk3 = v3\n"
         (properties [ { :xxx { :k1 :v1 "k2" "v2"}} {:aaa { :k3 :v3}}]))))
