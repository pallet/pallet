(ns pallet.crate.public-dns-if-no-nameserver-test
  (:use pallet.crate.public-dns-if-no-nameserver
        pallet.test-utils
        clojure.test)
  (:require
   [pallet.resource :as resource]))

(deftest invoke-test
  (is (build-resources
       []
       (public-dns-if-no-nameserver))))
