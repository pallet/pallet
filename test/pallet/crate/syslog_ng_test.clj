(ns pallet.crate.syslog-ng-test
  (:use pallet.crate.syslog-ng)
  (:use clojure.test
        pallet.test-utils)
  (:require
   [pallet.resource :as resource]
   [pallet.compute.jclouds :as jclouds]))

(deftest property-fmt-test
  (testing "single property"
    (is (= "group(logs);\n"
           (property-fmt {:group 'logs}))))
  (testing "multiple properties"
    (is (= "group(logs);\ndir_group(logs);\n"
           (property-fmt {:group 'logs :dir_group 'logs}))))
  (testing "string property"
    (is (= "bad_hostname(\"gconfd\");\n"
           (property-fmt {:bad_hostname "gconfd"}))))
  (testing "sub property"
    (is (= "tcp(ip(0.0.0.0) port(5000) max-connections(300));\n"
           (property-fmt
            {:tcp {:ip "0.0.0.0" :port 5000 :max-connections 300}}))))
  (testing "logic ops"
    (is (= "(facility(daemon,mail)  or level(debug,info,notice,warn)  or (facility(news)  and level(crit,err,notice)));\n"
           (property-fmt
            {:or {:facility "daemon,mail"
                  :level "debug,info,notice,warn"
                  :and {:facility "news"
                        :level "crit,err,notice"}}}))))
  (testing "vector"
    (is (= "filter(f1);\nfilter(f2);\n"
           (property-fmt
            {:filter [:f1 :f2]}))))
  (testing "some examples"
    (is (= "unix-stream(\"/dev/log\");\nudp();\nfile(\"f\" template(\"t\") template_escape(no));\n"
           (property-fmt
            {:unix-stream "/dev/log"
             :udp true
             :file {:value "f" :template "t" :template_escape "no"}})))))

(deftest configure-block-test
  (is (= "a {\nb(c);\n};\n" (configure-block "a" {:b "c"}))))

(deftest invoke-test
  (is (resource/build-resources
       [:target-node (jclouds/make-node "tag" :id "id" :ip "1.2.3.4")]
       (install)
       (set-server-ip)
       (iptables-accept))))
