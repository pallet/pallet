(ns pallet.blobstore.url-blobstore-test
  (:require
   [clojure.test :refer :all]
   [pallet.blobstore :refer [service]]
   [pallet.blobstore.url-blobstore :refer :all]))

(deftest url-blobstore-service-test
  (is (service :url-blobstore)))
