(ns pallet.action.package.epel-test
  (:use
   pallet.action.package.epel
   clojure.test
   [pallet.common.logging.logutils :only [logging-threshold-fixture]])
  (:require
   [pallet.action.package :as package]
   [pallet.build-actions :as build-actions]))

(use-fixtures :once (logging-threshold-fixture))

(deftest epel-test
  (is
   (build-actions/build-actions
    {:server {:packager :yum :image {:os-family :centos :os-version "5.5"}}}
    (add-epel))))
