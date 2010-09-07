(ns pallet.crate.sphinx-test
  (:use pallet.crate.sphinx
        clojure.test)
  (:require
   [pallet.resource :as resource]))

(deftest invoke-test
  (is (resource/build-resources
       []
       (sphinx))))
