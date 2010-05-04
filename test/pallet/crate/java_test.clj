(ns pallet.crate.java-test
  (:use [pallet.crate.java] :reload-all)
  (:require
   [pallet.core :as core]
   [pallet.target :as target]
   [pallet.template :as template]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils])
  (:use clojure.test
        pallet.test-utils
        [pallet.resource.package :only [package package-manager]]
        [pallet.resource :only [build-resources]]
        [pallet.stevedore :only [script]]))

(use-fixtures :each with-null-target)

(def pkg-config (target/with-target nil {}
                  (build-resources
                   []
                   (package-manager :universe)
                   (package-manager :multiverse)
                   (package-manager :update))))

(def noninteractive (script (package-manager-non-interactive)))

(defn debconf [pkg]
  (target/with-target nil {}
    (build-resources
     []
     (package-manager
      :debconf
      (str pkg " shared/present-sun-dlj-v1-1 note")
      (str pkg " shared/accepted-sun-dlj-v1-1 boolean true")))))

(deftest java-default-test
  (is (= (stevedore/do-script
          pkg-config
          (debconf "sun-java6-bin")
          (debconf "sun-java6-jdk")
          (build-resources [] (package "sun-java6-bin")
                           (package "sun-java6-jdk")))
         (pallet.resource/build-resources [] (java)))))

(deftest java-sun-test
  (is (= (stevedore/do-script
          pkg-config
          (debconf "sun-java6-bin")
          (debconf "sun-java6-jdk")
          (build-resources [] (package "sun-java6-bin")
                           (package "sun-java6-jdk")))
         (pallet.resource/build-resources []
          (java :sun :bin :jdk)))))

(deftest java-openjdk-test
  (is (= (build-resources [] (package "openjdk-6-jre"))
         (pallet.resource/build-resources []
          (java :openjdk :jre)))))

; ensure java is properly delaying execution
(core/defnode test-delayed-exec
  [:ubuntu]
  :bootstrap [(java :sun)])