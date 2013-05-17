(ns pallet.actions.direct.service-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions
    :refer [exec-checked-script
            remote-file
            service
            service-script
            with-service-restart]]
   [pallet.actions-impl :refer [service-script-path]]
   [pallet.build-actions :refer [build-actions]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.test-utils :refer [make-node no-location-info]]))

(use-fixtures :once (logging-threshold-fixture) no-location-info)

(deftest service-test
  (is (script-no-comment=
       "echo start tomcat\n/etc/init.d/tomcat start\n"
       (first (build-actions {} (service "tomcat")))))
  (is (script-no-comment=
       "echo stop tomcat\n/etc/init.d/tomcat stop\n"
       (first (build-actions {} (service "tomcat" :action :stop)))))
  (is (script-no-comment=
       (first
        (build-actions {}
          (exec-checked-script
           "Configure service tomcat"
           "update-rc.d tomcat defaults 20 20")))
       (first (build-actions {} (service "tomcat" :action :enable)))))
  (is (script-no-comment=
       (first
        (build-actions {}
          (exec-checked-script
           "Configure service tomcat"
           "/sbin/chkconfig tomcat on --level 2345")))
       (first (build-actions
                  {:server {:node (make-node "n" :os-family :centos)}}
                (service "tomcat" :action :enable))))))

(deftest with-restart-test
  (is (script-no-comment=

       (str "echo stop tomcat\n/etc/init.d/tomcat stop\n"
            "echo start tomcat\n/etc/init.d/tomcat start\n")
       (first (build-actions {}
                (with-service-restart "tomcat"))))))

(deftest init-script-test
  (is (script-no-comment=
       (first
        (build-actions {:phase-context "init-script"}
          (remote-file
           (service-script-path :initd "tomcat")
           :action :create
           :content "c"
           :owner "root"
           :group "root"
           :mode "0755")))
       (first
        (build-actions {}
          (service-script "tomcat" :content "c"))))))
