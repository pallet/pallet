(ns pallet.crate.crontab-test
  (:use [pallet.crate.crontab] :reload-all)
  (:require
   [pallet.template :only [apply-templates]]
   [pallet.core :as core]
   [pallet.resource :as resource]
   [pallet.target :as target])
  (:use clojure.test
        pallet.test-utils))

(deftest crontab-test
  (is (= "cat > $(getent passwd quote user | cut -d: -f6)/crontab.in <<EOF\ncontents\nEOF\nchown  fred $(getent passwd quote user | cut -d: -f6)/crontab.in\nchmod  0600 $(getent passwd quote user | cut -d: -f6)/crontab.in\ncrontab -u fred $(getent passwd quote user | cut -d: -f6)/crontab.in\n"
         (resource/build-resources [] (crontab "fred" :content "contents")))))

(deftest system-crontab-test
  (is (= "cat > /etc/cron.d/fred <<EOF\ncontents\nEOF\nchown  root /etc/cron.d/fred\nchgrp  root /etc/cron.d/fred\nchmod  0644 /etc/cron.d/fred\n"
         (resource/build-resources [] (system-crontab "fred" :content "contents")))))
