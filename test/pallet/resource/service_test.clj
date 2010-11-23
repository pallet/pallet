(ns pallet.resource.service-test
  (:use pallet.resource.service)
  (:use [pallet.stevedore :only [script]]
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.resource.remote-file :as remote-file]))

(deftest service-test
  (is (= "/etc/init.d/tomcat start\n"
         (first (build-resources [] (service "tomcat")))))
  (is (= "/etc/init.d/tomcat stop\n"
         (first (build-resources [] (service "tomcat" :action :stop))))))

(deftest with-restart-test
  (is (= "/etc/init.d/tomcat stop\n/etc/init.d/tomcat start\n"
         (first (build-resources [] (with-restart "tomcat"))))))

(deftest init-script-test
  (is (= (first (build-resources
                 []
                 (remote-file/remote-file
                  "/etc/init.d/tomcat"
                  :action :create
                  :content "c"
                  :owner "root"
                  :group "root"
                  :mode "0755")))
         (first (build-resources [] (init-script "tomcat" :content "c"))))))
