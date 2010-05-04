(ns pallet.crate.java-test
  (:use [pallet.crate.java] :reload-all)
  (:require
   [pallet.core :as core]
   [pallet.resource :as resource]
   [pallet.resource.package :as package]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target]
   [pallet.template :as template]
   [pallet.utils :as utils])

  (:use clojure.test
        pallet.test-utils))

(use-fixtures :each with-null-target)

(def pkg-config
     (target/with-target nil {:image [:ubuntu]}
       (stevedore/chain-commands
        (package/package-manager* :universe)
        (package/package-manager* :multiverse)
        (package/package-manager* :update))))

(def noninteractive (stevedore/script (package-manager-non-interactive)))

(defn debconf [pkg]
  (package/package-manager*
   :debconf
   (str pkg " shared/present-sun-dlj-v1-1 note")
   (str pkg " shared/accepted-sun-dlj-v1-1 boolean true")))

(deftest java-default-test
  (is (= (target/with-target nil {}
           (stevedore/checked-commands
            "Install java"
            pkg-config
            (debconf "sun-java6-bin")
            (package/package* "sun-java6-bin")
            (debconf "sun-java6-jdk")
            (package/package* "sun-java6-jdk")))
         (test-resource-build
          [nil {}]
          (java)))))

(deftest java-sun-test
  (is (= (target/with-target nil {}
           (stevedore/checked-commands
            "Install java"
            pkg-config
            (debconf "sun-java6-bin")
            (package/package* "sun-java6-bin")
            (debconf "sun-java6-jdk")
            (package/package* "sun-java6-jdk")))
         (test-resource-build
          [nil {}]
          (java :sun :bin :jdk)))))

(deftest java-openjdk-test
  (is (= (target/with-target nil {}
           (stevedore/checked-commands
            "Install java"
            (package/package* "openjdk-6-jre")))
         (test-resource-build [nil {}]
          (java :openjdk :jre)))))


; ensure java is properly delaying execution
(core/defnode test-delayed-exec
  [:ubuntu]
  :bootstrap [(java :sun)])
