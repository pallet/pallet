(ns pallet.crate.jetty-test
  (:use
   pallet.crate.jetty
   clojure.test)
  (:require
   [pallet.resource :as resource]))

(deftest invoke-test
  (is (resource/build-resources
       []
       (jetty)
       (configure "")
       (server "")
       (ssl "")
       (context "" "")
       (deploy "" :content "c"))))
