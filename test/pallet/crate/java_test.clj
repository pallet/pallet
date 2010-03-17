(ns pallet.crate.java-test
  (:use [pallet.crate.java] :reload-all)
  (:require [pallet.template :only [apply-templates]])
  (:use clojure.test
        pallet.test-utils
        [pallet.resource.package :only [package package-manager]]
        [pallet.resource :only [build-resources]]
        [clojure.contrib.java-utils :only [file]]))

(def pkg-congif (build-resources (package-manager :universe)
                                 (package-manager :multiverse)
                                 (package-manager :update)))

(deftest java-default-test
  (is (= (str pkg-congif (build-resources (package "sun-java6-jdk")))
         (pallet.resource/build-resources (java)))))

(deftest java-sun-test
  (is (= (str pkg-congif (build-resources (package "sun-java6-jdk")))
         (pallet.resource/build-resources
          (java :sun :jdk)))))

(deftest java-openjdk-test
  (is (= (str pkg-congif (build-resources (package "openjdk-6-jre")))
         (pallet.resource/build-resources
          (java :openjdk :jre)))))
