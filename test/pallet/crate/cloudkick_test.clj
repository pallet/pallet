(ns pallet.crate.cloudkick-test
  (:use pallet.crate.cloudkick)
  (:use clojure.test)
  (:require
   [pallet.core :as core]
   [pallet.stevedore :as stevedore]
   [pallet.resource :as resource]
   [pallet.resource.hostinfo :as hostinfo]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.package :as package]
   [pallet.target :as target]))

(deftest cloudkick-test
  (core/defnode a {:os-family :ubuntu})
  (let [request {:node-type a}]
    (is (= (str
            (package/package-source*
             request
             "cloudkick"
             :aptitude
             {:url "http://packages.cloudkick.com/ubuntu"
              :key-url "http://packages.cloudkick.com/cloudkick.packages.key"}
             :yum { :url (str "http://packages.cloudkick.com/redhat/"
                              (hostinfo/architecture))})
            (package/package-manager* request :update)
            (stevedore/checked-commands
             "Packages"
             (stevedore/script (package/package-manager-non-interactive))
             (package/package* request "cloudkick-agent"))
            (remote-file/remote-file*
             request
             "/etc/cloudkick.conf"
             :content
             "oauth_key key\noauth_secret secret\ntags any\nname node\n\n\n\n"))
           (first
            (resource/build-resources
             [:node-type a] (cloudkick "node" "key" "secret")))))))
