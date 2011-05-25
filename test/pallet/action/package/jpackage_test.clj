(ns pallet.action.package.jpackage-test
  (:use
   pallet.action.package.jpackage
   clojure.test)
  (:require
   [pallet.action.package :as package]
   [pallet.build-actions :as build-actions]))

(deftest jpackage-test
  (is
   (build-actions/build-actions
    {:server {:packager :yum :image {:os-family :centos :os-version "5.5"}}}
    (jpackage-utils)
    (add-jpackage)
    (package-manager-update-jpackage))))
