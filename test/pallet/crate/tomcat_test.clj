(ns pallet.crate.tomcat-test
  (:use [pallet.crate.tomcat] :reload-all)
  (:require [pallet.template :only [apply-templates]])
  (:use clojure.test
        pallet.test-utils
        [pallet.resource.package :only [package package-manager]]
        [pallet.resource :only [build-resources]]
        [pallet.stevedore :only [script]]
        [pallet.utils :only [cmd-join]]
        [clojure.contrib.java-utils :only [file]]))

(deftest tomcat-test
  (is (= "debconf-set-selections <<EOF\ndebconf debconf/frontend select noninteractive\ndebconf debconf/frontend seen false\nEOF\naptitude install -y  tomcat6\n"
         (build-resources (tomcat)))))

(deftest tomcat-deploy-test
  (is (= "cp file.war /var/lib/tomcat6/webapps/\nservice tomcat6 action restart\n"
         (build-resources (tomcat-deploy "file.war")))))

(deftest tomcat-policy-test
  (is (= "cat > /etc/tomcat6/policy.d/100hudson <<'EOF'\ngrant codeBase \"file:${catalina.base}/webapps/hudson/-\" {\npermission java.lang.RuntimePermission \"getAttribute\";\n};\nEOF\n"
         (build-resources
          (tomcat-policy
           100 "hudson"
           {"file:${catalina.base}/webapps/hudson/-"
            ["permission java.lang.RuntimePermission \"getAttribute\""]})))))

(deftest tomcat-application-conf-test
  (is (= "cat > /etc/tomcat6/Catalina/localhost/hudson.xml <<'EOF'\n<?xml version='1.0' encoding='utf-8'?>\n<Context docBase=\"/srv/hudson/hudson.war\">\n<Environment name=\"HUDSON_HOME\"/>\n</Context>\nEOF\n"
         (build-resources
          (tomcat-application-conf
           "hudson"
           "<?xml version='1.0' encoding='utf-8'?>
<Context docBase=\"/srv/hudson/hudson.war\">
<Environment name=\"HUDSON_HOME\"/>
</Context>")))))
