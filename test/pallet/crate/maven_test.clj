(ns pallet.crate.maven-test
  (:use
   pallet.crate.maven
   clojure.test
   pallet.test-utils)
  (:require
   [pallet.resource :as resource]
   [pallet.resource.remote-directory :as remote-directory]))

(deftest download-test
  (is (= (first
          (build-resources
           []
           (remote-directory/remote-directory
            "/opt/maven2"
            :url (maven-download-url "2.2.1")
            :md5 (maven-download-md5 "2.2.1")
            :unpack :tar :tar-options "xj"))))
      (first
       (build-resources
        []
        (download :version "2.2.1")))))
