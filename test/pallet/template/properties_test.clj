(ns pallet.template.properties-test
  (:use pallet.template.properties)
  (:use clojure.test)
  (:require [clojure.string :as string]))


(deftest properties-test
  (letfn [(f [kv] (format "%s = %s\n" (name (key kv)) (name (val kv))))]
    (is (= (str "[xxx]\n" (string/join (map f {:k1 :v1 "k2" "v2"}))
                "[aaa]\n" (string/join (map f {:k3 :v3})))
           (properties [{:xxx {:k1 :v1 "k2" "v2"}} {:aaa {:k3 :v3}}])))))
