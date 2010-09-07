(ns pallet.crate.php-test
  (:use pallet.crate.php
        clojure.test)
  (:require
   [pallet.resource :as resource]))

(deftest invoke-test
  (is (resource/build-resources
       []
       (php)
       (php "extension"))))
