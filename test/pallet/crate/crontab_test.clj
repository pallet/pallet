(ns pallet.crate.crontab-test
  (:use pallet.crate.crontab)
  (:require
   [pallet.template :only [apply-templates]]
   [pallet.core :as core]
   [pallet.stevedore :as stevedore]
   [pallet.resource :as resource]
   [pallet.resource.remote-file :as remote-file]
   [pallet.target :as target])
  (:use clojure.test
        pallet.test-utils))

(deftest crontab-test
  (is (= (stevedore/do-script
          (remote-file/remote-file* {}
           "$(getent passwd user | cut -d: -f6)/crontab.in"
           :content "contents" :owner "fred" :mode "0600")
          "crontab -u fred $(getent passwd user | cut -d: -f6)/crontab.in\n")
         (first
          (resource/build-resources
           [] (crontab "fred" :content "contents"))))))

(deftest system-crontab-test
  (is (= (remote-file/remote-file* {}
          "/etc/cron.d/fred"
          :content "contents" :owner "root" :group "root" :mode "0644")
         (first
          (resource/build-resources
           [] (system-crontab "fred" :content "contents"))))))
