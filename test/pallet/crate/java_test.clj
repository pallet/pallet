(ns pallet.crate.java-test
  (:use [pallet.crate.java] :reload-all)
  (:require [pallet.template :only [apply-templates]]
            [pallet.resource :only [build-resources]])
  (:use clojure.test
        pallet.test-utils
        [clojure.contrib.java-utils :only [file]]))

(deftest java-default-test
  (is (= "aptitude install -y  sun-java6-jdk"
         (pallet.resource/build-resources
          (java)))))

(deftest java-sun-test
  (is (= "aptitude install -y  sun-java6-jdk"
         (pallet.resource/build-resources
          (java :sun :jdk)))))

(deftest java-openjdk-test
  (is (= "aptitude install -y  openjdk-6-jre"
         (pallet.resource/build-resources
          (java :openjdk :jre)))))
