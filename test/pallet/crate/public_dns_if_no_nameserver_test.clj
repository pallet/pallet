(ns pallet.crate.public-dns-if-no-nameserver-test
  (:use pallet.crate.public-dns-if-no-nameserver
        clojure.test)
  (:require
   [pallet.resource :as resource]))

(deftest invoke-test
  (is (resource/build-resources
       []
       (public-dns-if-no-nameserver))))
