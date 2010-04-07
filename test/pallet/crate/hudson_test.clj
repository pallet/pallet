(ns pallet.crate.hudson-test
  (:use [pallet.crate.hudson] :reload-all)
  (:require [pallet.template :only [apply-templates]])
  (:use clojure.test
        pallet.test-utils
        [pallet.resource.package :only [package package-manager]]
        [pallet.resource :only [build-resources]]
        [pallet.stevedore :only [script]]
        [pallet.utils :only [cmd-join]]
        [clojure.contrib.java-utils :only [file]]))

(deftest hudson-test
  (is (= "mkdir -p /var/hudson\nchown  root /var/hudson\nchgrp  tomcat6 /var/hudson\nchmod  775 /var/hudson\nif [ ! \\( -e /var/hudson/hudson.war -a \\( \"680e1525fca0562cfd19552b8d8174e2\" == \"$(md5sum /var/hudson/hudson.war | cut -f1 -d' ')\" \\) \\) ]; then wget -O /var/hudson/hudson.war http://hudson-ci.org/latest/hudson.war;fi\necho MD5 sum is $(md5sum /var/hudson/hudson.war)\ncat > /etc/tomcat6/policy.d/99hudson.policy <<'EOF'\ngrant codeBase \"file:${catalina.base}/webapps/hudson/-\" {\npermission java.security.AllPermission;\n};\ngrant codeBase \"file:/var/hudson/-\" {\npermission java.security.AllPermission;\n};\nEOF\ncat > /etc/tomcat6/Catalina/localhost/hudson.xml <<'EOF'\n<?xml version=\"1.0\" encoding=\"utf-8\"?>\n <Context\n privileged=\"true\"\n path=\"/hudson\"\n allowLinking=\"true\"\n swallowOutput=\"true\"\n >\n <Environment\n name=\"HUDSON_HOME\"\n value=\"/var/hudson\"\n type=\"java.lang.String\"\n override=\"false\"/>\n </Context>\nEOF\ncp /var/hudson/hudson.war /var/lib/tomcat6/webapps/\n/etc/init.d/tomcat6 stop\n/etc/init.d/tomcat6 start\n"
         (build-resources (hudson)))))


