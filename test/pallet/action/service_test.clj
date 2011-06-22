(ns pallet.action.service-test
  (:use pallet.action.service)
  (:use [pallet.stevedore :only [script]]
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.action.exec-script :as exec-script]
   [pallet.action.remote-file :as remote-file]
   [pallet.build-actions :as build-actions]))

(deftest service-test
  (is (= "/etc/init.d/tomcat start\n"
         (first (build-actions/build-actions
                 {} (service "tomcat")))))
  (is (= "/etc/init.d/tomcat stop\n"
         (first (build-actions/build-actions
                 {} (service "tomcat" :action :stop)))))
  (is (= (first
          (build-actions/build-actions
           {}
           (exec-script/exec-checked-script
            "Configure service tomcat"
            "update-rc.d tomcat defaults 20 20")))
         (first (build-actions/build-actions
                 {} (service "tomcat" :action :enable)))))
  (is (= (first
          (build-actions/build-actions
           {}
           (exec-script/exec-checked-script
            "Configure service tomcat"
            "/sbin/chkconfig tomcat on --level 2345")))
         (first (build-actions/build-actions
                 {:packager :yum} (service "tomcat" :action :enable))))))

(deftest with-restart-test
  (is (= "/etc/init.d/tomcat stop\n/etc/init.d/tomcat start\n"
         (first (build-actions/build-actions
                 {} (with-restart "tomcat"))))))

(deftest init-script-test
  (is (= (first (build-actions/build-actions
                 {}
                 (remote-file/remote-file
                  "/etc/init.d/tomcat"
                  :action :create
                  :content "c"
                  :owner "root"
                  :group "root"
                  :mode "0755")))
         (first (build-actions/build-actions
                 {} (init-script "tomcat" :content "c"))))))
