(ns pallet.crate.postfix-test
  (:use pallet.crate.postfix
        clojure.test)
  (:require
   [pallet.resource :as resource]))

(deftest invoke-test
  (is (resource/build-resources
       []
       (postfix "a.com" :internet-site))))
