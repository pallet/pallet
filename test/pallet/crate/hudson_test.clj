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
  (is (= "if getent passwd hudson; then usermod --shell /bin/false --home /var/lib/hudson hudson;else useradd --shell /bin/false --home /var/lib/hudson hudson;fi\nwget -O /tmp/hudson.war http://hudson-ci.org/latest/hudson.war\necho MD5 sum is $(md5sum /tmp/hudson.war)\ncp /tmp/hudson.war /var/lib/tomcat6/webapps/\nservice tomcat6 action restart\ncat > /etc/tomcat6/policy.d/99hudson <<'EOF'\ngrant codeBase \"file:${catalina.base}/webapps/hudson/-\" {\npermission java.security.AllPermission;\n};\nEOF\n"
         (build-resources (hudson)))))


