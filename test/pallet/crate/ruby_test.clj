(ns pallet.crate.ruby-test
  (:use pallet.crate.ruby
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.resource :as resource]))

(deftest invoke-test
  (is (build-resources
       []
       (ruby)
       (ruby-packages))))
