(ns pallet.resource.service-test
  (:use pallet.resource.service)
  (:use [pallet.stevedore :only [script]]
        [pallet.resource :only [build-resources]]
        clojure.test
        pallet.test-utils))

(use-fixtures :each with-null-target)

(deftest service-test
  (is (= "/etc/init.d/tomcat start\n"
         (build-resources [] (service "tomcat"))))
  (is (= "/etc/init.d/tomcat stop\n"
         (build-resources [] (service "tomcat" :action :stop)))))

(deftest with-restart-test
  (is (= "/etc/init.d/tomcat stop\n/etc/init.d/tomcat start\n"
         (build-resources [] (with-restart "tomcat")))))
