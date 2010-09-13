(ns pallet.crate.java-test
  (:use pallet.crate.java)
  (:require
   [pallet.core :as core]
   [pallet.resource :as resource]
   [pallet.resource.package :as package]
   [pallet.stevedore :as stevedore]
   [pallet.script :as script]
   [pallet.target :as target]
   [pallet.template :as template]
   [pallet.utils :as utils])
  (:use clojure.test
        pallet.test-utils))

(use-fixtures :once with-ubuntu-script-template)

(def pkg-config
  (script/with-template [:ubuntu]
    (stevedore/chain-commands
     (package/package-manager* ubuntu-request :universe)
     (package/package-manager* ubuntu-request :multiverse)
     (package/package-manager* ubuntu-request :update))))

(def noninteractive
  (script/with-template [:ubuntu]
    (stevedore/script (package-manager-non-interactive))))

(defn debconf [pkg]
  (script/with-template [:ubuntu]
    (package/package-manager*
     ubuntu-request
     :debconf
     (str pkg " shared/present-sun-dlj-v1-1 note")
     (str pkg " shared/accepted-sun-dlj-v1-1 boolean true"))))

(deftest java-default-test
  (is (= (stevedore/checked-commands
          "Install java"
          pkg-config
          (debconf "sun-java6-bin")
          (package/package* ubuntu-request  "sun-java6-bin")
          (debconf "sun-java6-jdk")
          (package/package* ubuntu-request  "sun-java6-jdk"))
         (first
          (resource/build-resources
           [:node-type {:image {:os-family :ubuntu}}]
           (java))))))

(deftest java-sun-test
  (is (= (stevedore/checked-commands
          "Install java"
          pkg-config
          (debconf "sun-java6-bin")
          (package/package* ubuntu-request "sun-java6-bin")
          (debconf "sun-java6-jdk")
          (package/package* ubuntu-request "sun-java6-jdk"))
         (first
          (resource/build-resources
           []
           (java :sun :bin :jdk))))))

(deftest java-openjdk-test
  (is (= (stevedore/checked-commands
          "Install java"
          (package/package* ubuntu-request "openjdk-6-jre"))
         (first
          (resource/build-resources
           []
           (java :openjdk :jre))))))


(deftest invoke-test
  (is
   (resource/build-resources
    []
    (java :openjdk :jdk)
    (jce-policy-file "f" :content ""))))
