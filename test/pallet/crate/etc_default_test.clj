(ns pallet.crate.etc-default-test
 (:use clojure.test)
 (:require [pallet.crate.etc-default :as default]
   [pallet.resource :as resource]
   [pallet.target :as target]))

(deftest test-tomcat-defaults
  (is (= "if [ ! -e /etc/default/tomcat6.orig ]; then cp /etc/default/tomcat6 /etc/default/tomcat6.orig;fi\necho \"remote-file /etc/default/tomcat6...\"\n{ { cat > /etc/default/tomcat6 <<EOF\nJAVA_OPTS=\"-Djava.awt.headless=true -Xmx1024m\"\nJSP_COMPILER=\"javac\"\nEOF\n } && chown  root:root /etc/default/tomcat6 && chmod  644 /etc/default/tomcat6; } || { echo remote-file /etc/default/tomcat6 failed ; exit 1 ; } >&2 \necho \"...done\"\n"
        (target/with-target nil [:ubuntu]
          (resource/build-resources []
            (default/write "tomcat6"
              :JAVA_OPTS "-Djava.awt.headless=true -Xmx1024m"
              "JSP_COMPILER" "javac"))))))

(deftest test-quoting
  (is (= "\"\\\"quoted value\\\"\""
        (#'default/quoted "\"quoted value\""))))