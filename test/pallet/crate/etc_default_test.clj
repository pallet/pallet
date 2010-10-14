(ns pallet.crate.etc-default-test
 (:use
  clojure.test
  pallet.test-utils)
 (:require
  [pallet.crate.etc-default :as default]
  [pallet.resource :as resource]
  [pallet.stevedore :as stevedore]
  [pallet.resource.remote-file :as remote-file]
  [pallet.target :as target]))

(use-fixtures :once with-ubuntu-script-template)

(deftest test-tomcat-defaults
  (is (= (stevedore/do-script
          (remote-file/remote-file*
           {}
           "/etc/default/tomcat6"
           :owner "root:root"
           :mode 644
           :content "JAVA_OPTS=\"-Djava.awt.headless=true -Xmx1024m\"\nJSP_COMPILER=\"javac\""))
         (first
          (build-resources
           [:node-type {:image {:os-family :ubuntu}}]
           (default/write "tomcat6"
             :JAVA_OPTS "-Djava.awt.headless=true -Xmx1024m"
             "JSP_COMPILER" "javac")))))
  (is (= (stevedore/do-script
          (remote-file/remote-file*
           {}
           "/etc/tomcat/tomcat6"
           :owner "root:root"
           :mode 644
           :content "JAVA_OPTS=\"-Djava.awt.headless=true\""))
         (first
          (build-resources
           [:node-type {:image {:os-family :ubuntu}}]
           (default/write "/etc/tomcat/tomcat6"
             :JAVA_OPTS "-Djava.awt.headless=true"))))))
