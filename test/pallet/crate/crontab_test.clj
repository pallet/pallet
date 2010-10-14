(ns pallet.crate.crontab-test
  (:use pallet.crate.crontab)
  (:require
   [pallet.template :only [apply-templates]]
   [pallet.core :as core]
   [pallet.resource.exec-script :as exec-script]
   [pallet.stevedore :as stevedore]
   [pallet.resource :as resource]
   [pallet.resource.remote-file :as remote-file]
   [pallet.target :as target])
  (:use clojure.test
        pallet.test-utils))

(deftest crontab-test
  (is (= (first
          (build-resources
           []
           (remote-file/remote-file
            "$(getent passwd user | cut -d: -f6)/crontab.in"
            :content "contents" :owner "fred" :mode "0600")
           (exec-script/exec-checked-script
            "Load crontab"
            ("crontab -u fred"
             "$(getent passwd user | cut -d: -f6)/crontab.in\n"))))
         (first
          (build-resources
           [] (crontab "fred" :content "contents"))))))

(deftest system-crontab-test
  (is (= (first
          (build-resources
           []
           (remote-file/remote-file
            "/etc/cron.d/fred"
            :content "contents" :owner "root" :group "root" :mode "0644")))
         (first
          (build-resources
           [] (system-crontab "fred" :content "contents"))))))
