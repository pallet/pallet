(ns pallet.crate.crontab-test
  (:use pallet.crate.crontab)
  (:require
   [pallet.build-actions :as build-actions]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.remote-file :as remote-file])
  (:use clojure.test
        pallet.test-utils))

(deftest crontab-test
  (is (= (first
          (build-actions/build-actions
           {}
           (remote-file/remote-file
            "$(getent passwd user | cut -d: -f6)/crontab.in"
            :content "contents" :owner "fred" :mode "0600")
           (exec-script/exec-checked-script
            "Load crontab"
            ("crontab -u fred"
             "$(getent passwd user | cut -d: -f6)/crontab.in\n"))))
         (first
          (build-actions/build-actions
           {} (crontab "fred" :content "contents"))))))

(deftest system-crontab-test
  (is (= (first
          (build-actions/build-actions
           {}
           (remote-file/remote-file
            "/etc/cron.d/fred"
            :content "contents" :owner "root" :group "root" :mode "0644")))
         (first
          (build-actions/build-actions
           {} (system-crontab "fred" :content "contents"))))))
