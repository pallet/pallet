(ns pallet.crate.postfix-test
  (:use pallet.crate.postfix
        pallet.test-utils
        clojure.test)
  (:require
   [pallet.resource :as resource]))

(deftest invoke-test
  (is (build-resources
       []
       (postfix "a.com" :internet-site))))
