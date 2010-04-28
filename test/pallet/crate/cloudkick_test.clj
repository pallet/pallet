(ns pallet.crate.cloudkick-test
  (:use pallet.crate.cloudkick :reload-all)
  (:use clojure.test)
  (:require
   [pallet.core :as core]
   [pallet.stevedore :as stevedore]
   [pallet.resource :as resource]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.package :as package]
   [pallet.target :as target]))

(deftest cloudkick-test
  (core/defnode a [:ubuntu])
  (target/with-target nil {:tag :a :image [:ubuntu]}
    (is (= (str
            (remote-file/remote-file*
             "/etc/apt/sources.list.d/cloudkick.list"
             :content
             "deb http://packages.cloudkick.com/ubuntu $(lsb_release -c -s) main\n")
            (remote-file/remote-file*
             "aptkey.tmp"
             :url "http://packages.cloudkick.com/cloudkick.packages.key")
            "apt-key add aptkey.tmp\n"
            (package/package-manager* :update)
            (stevedore/script (package/package-manager-non-interactive))
            (package/package* "cloudkick-agent")
            \newline
            (remote-file/remote-file*
             "/etc/cloudkick.conf"
             :content "oauth_key key\noauth_secret secret\ntags any\nname node\n\n\n\n"))
           (resource/build-resources [] (cloudkick "node" "key" "secret"))))))
