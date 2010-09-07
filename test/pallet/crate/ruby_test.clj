(ns pallet.crate.ruby-test
  (:use pallet.crate.ruby
        clojure.test)
  (:require
   [pallet.resource :as resource]))

(deftest invoke-test
  (is (resource/build-resources
       []
       (ruby)
       (ruby-packages))))
