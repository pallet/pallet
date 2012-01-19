(ns pallet.actions.direct.service-test
  (:use
   clojure.test
   pallet.test-utils
   [pallet.actions
    :only [service with-service-restart init-script
           exec-checked-script remote-file]]
   [pallet.build-actions :as build-actions]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.stevedore :only [script]])
  (:require
   pallet.actions.direct.exec-script
   pallet.actions.direct.remote-file
   pallet.actions.direct.service))

(use-fixtures :once (logging-threshold-fixture))

(deftest service-test
  (is (= "echo start tomcat\n/etc/init.d/tomcat start\n"
         (first (build-actions
                 {} (service "tomcat")))))
  (is (= "echo stop tomcat\n/etc/init.d/tomcat stop\n"
         (first (build-actions
                 {} (service "tomcat" :action :stop)))))
  (is (= (first
          (build-actions
           {}
           (exec-checked-script
            "Configure service tomcat"
            "update-rc.d tomcat defaults 20 20")))
         (first (build-actions
                 {} (service "tomcat" :action :enable)))))
  (is (= (first
          (build-actions
           {}
           (exec-checked-script
            "Configure service tomcat"
            "/sbin/chkconfig tomcat on --level 2345")))
         (first (build-actions
                 {:packager :yum} (service "tomcat" :action :enable))))))

(deftest with-restart-test
  (is (= (str "echo stop tomcat\n/etc/init.d/tomcat stop\n\n"
              "echo start tomcat\n/etc/init.d/tomcat start\n")
         (first (build-actions
                 {}
                 (with-service-restart "tomcat"))))))

(deftest init-script-test
  (is (= (first
          (build-actions {:phase-context "init-script"}
            (remote-file
             "/etc/init.d/tomcat"
             :action :create
             :content "c"
             :owner "root"
             :group "root"
             :mode "0755")))
         (first
          (build-actions {}
            (init-script "tomcat" :content "c"))))))
