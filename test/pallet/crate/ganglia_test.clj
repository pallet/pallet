(ns pallet.crate.ganglia-test
  (:use pallet.crate.ganglia)
  (:use clojure.test
        pallet.test-utils)
  (:require
   [pallet.compute.jclouds :as jclouds]
   [pallet.resource :as resource]))

(deftest format-value-test
  (testing "basic map"
    (is (= "a {\nb = \"c\"\nd = 1\n}\n"
           (format-value {:a {:b "c" :d 1}}))))
  (testing "nested map"
    (is (= "a {\nb {\nc = \"d\"\n}\n}\n"
           (format-value {:a {:b {:c "d"}}}))))
  (testing "array value"
    (is (= "a {\nb {\nc = 1\n}\nb {\nd = 2\n}\n}\n"
           (format-value {:a {:b [{:c 1} {:d 2}]}}))))
  (testing "include"
    (is (= "include (\"/a/b/c\")\n"
           (format-value {:include "/a/b/c"})))))

(testing "invoke"
  (is (resource/build-resources
      [:target-node (jclouds/make-node "tag" :id "id")
       :node-type {:image {:os-family :ubuntu} :tag :tag}]
       (install)
       (monitor)
       (configure)
       (metrics {})
       (check-ganglia-script)
       (nagios-monitor-metric "m" 90 100))))
