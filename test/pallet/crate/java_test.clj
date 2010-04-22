(ns pallet.crate.java-test
  (:use [pallet.crate.java] :reload-all)
  (:require [pallet.template :only [apply-templates]])
  (:use clojure.test
        pallet.test-utils
        [pallet.resource.package :only [package package-manager]]
        [pallet.resource :only [build-resources]]
        [pallet.stevedore :only [script]]
        [pallet.utils :only [cmd-join]]))

(def pkg-config (build-resources []
                  (package-manager :universe)
                  (package-manager :multiverse)
                  (package-manager :update)))

(def noninteractive (script (package-manager-non-interactive)))

(defn debconf [pkg]
  (build-resources []
   (package-manager
    :debconf
    (str pkg " shared/present-sun-dlj-v1-1 note")
    (str pkg " shared/accepted-sun-dlj-v1-1 boolean true"))))

(deftest java-default-test
  (is (= (cmd-join
          [pkg-config
           (debconf "sun-java6-bin")
           (debconf "sun-java6-jdk")
           (build-resources [] (package "sun-java6-bin")
                            (package "sun-java6-jdk"))])
         (pallet.resource/build-resources [] (java)))))

(deftest java-sun-test
  (is (= (cmd-join
          [pkg-config
           (debconf "sun-java6-bin")
           (debconf "sun-java6-jdk")
           (build-resources [] (package "sun-java6-bin")
                            (package "sun-java6-jdk"))])
         (pallet.resource/build-resources []
          (java :sun :bin :jdk)))))

(deftest java-openjdk-test
  (is (= (build-resources [] (package "openjdk-6-jre"))
         (pallet.resource/build-resources []
          (java :openjdk :jre)))))
