(ns pallet.crate.gpg-test
  (:use pallet.crate.gpg)
  (:require
   [pallet.resource :as resource]
   [pallet.resource.package :as package])
  (:use clojure.test
        pallet.test-utils))

(deftest invoke-test
  (is
   (build-resources
    []
    (gpg)
    (import-key :content "not ans export" :user "fred"))))
