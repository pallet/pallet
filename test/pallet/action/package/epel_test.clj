(ns pallet.action.package.epel-test
  (:use
   pallet.action.package.epel
   clojure.test)
  (:require
   [pallet.action.package :as package]
   [pallet.build-actions :as build-actions]))

(deftest epel-test
  (is
   (build-actions/build-actions
    {:server {:packager :yum :image {:os-family :centos :os-version "5.5"}}}
    (add-epel))))
