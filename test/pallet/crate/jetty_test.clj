(ns pallet.crate.jetty-test
  (:use
   pallet.crate.jetty
   clojure.test
   pallet.test-utils)
  (:require
   [pallet.resource :as resource]))

(deftest invoke-test
  (is (build-resources
       []
       (jetty)
       (configure "")
       (server "")
       (ssl "")
       (context "" "")
       (deploy "" :content "c"))))
