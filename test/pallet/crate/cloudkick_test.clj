(ns pallet.crate.cloudkick-test
  (:use pallet.crate.cloudkick :reload-all)
  (:use clojure.test)
  (:require
   [pallet.core :as core]
   [pallet.resource :as resource]
   [pallet.target :as target]))

(deftest cloudkick-test
  (core/defnode a [:ubuntu])
  (target/with-target nil a
    (is (= "debconf-set-selections <<EOF\ndebconf debconf/frontend select noninteractive\ndebconf debconf/frontend seen false\nEOF\naptitude install -y  cloudkick-agent\ncat > /etc/cloudkick.conf <<EOF\noauth_key key\noauth_secret secret\ntags any\nname node\n\n\n\n\nEOF\ncat > /etc/apt/sources.list.d/cloudkick <<EOF\ndeb http://packages.cloudkick.com/ubuntu $(lsb_release -c -s) main\n\nEOF\nwget -O aptkey.tmp http://packages.cloudkick.com/cloudkick.packages.key\necho MD5 sum is $(md5sum aptkey.tmp)\napt-key add aptkey.tmp\n"
           (resource/build-resources [] (cloudkick "node" "key" "secret"))))))
