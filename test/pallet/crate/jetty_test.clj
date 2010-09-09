(ns pallet.crate.jetty-test
  (:use
   pallet.crate.jetty
   clojure.test)
  (:require
   [pallet.resource :as resource]
   [pallet.compute :as compute]))

(deftest invoke-test
  (is (resource/build-resources
       [:target-node (compute/make-node "tag" :id "id")]
       (jetty)
       (configure "")
       (server "")
       (ssl "")
       (context "" "")
       (deploy "" :content "c"))))
