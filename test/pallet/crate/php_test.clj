(ns pallet.crate.php-test
  (:use pallet.crate.php
        pallet.test-utils
        clojure.test)
  (:require
   [pallet.resource :as resource]))

(deftest invoke-test
  (is (build-resources
       []
       (php)
       (php "extension"))))
