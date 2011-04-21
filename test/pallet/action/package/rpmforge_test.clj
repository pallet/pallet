(ns pallet.action.package.rpmforge-test
  (:use
   pallet.action.package.rpmforge
   clojure.test)
  (:require
   [pallet.action.package :as package]
   [pallet.build-actions :as build-actions]))

(deftest rpmforge-test
  (is
   (build-actions/build-actions
    {:server {:packager :yum :image {:os-family :centos :os-version "5.5"}}}
    (add-rpmforge))))
