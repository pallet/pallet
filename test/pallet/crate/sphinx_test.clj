(ns pallet.crate.sphinx-test
  (:use pallet.crate.sphinx
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.resource :as resource]))

(deftest invoke-test
  (is (build-resources
       []
       (sphinx))))
