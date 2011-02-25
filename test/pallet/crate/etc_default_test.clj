(ns pallet.crate.etc-default-test
 (:use
  clojure.test
  pallet.test-utils)
 (:require
  [pallet.action :as action]
  [pallet.build-actions :as build-actions]
  [pallet.action.remote-file :as remote-file]
  [pallet.crate.etc-default :as default]
  [pallet.stevedore :as stevedore]))

(use-fixtures :once with-ubuntu-script-template)

(def remote-file* (action/action-fn remote-file/remote-file-action))

(deftest test-tomcat-defaults
  (is (= (stevedore/do-script
          (remote-file*
           {}
           "/etc/default/tomcat6"
           :owner "root:root"
           :mode 644
           :content "JAVA_OPTS=\"-Djava.awt.headless=true -Xmx1024m\"\nJSP_COMPILER=\"javac\""))
         (first
          (build-actions/build-actions
           {:server {:image {:os-family :ubuntu}}}
           (default/write "tomcat6"
             :JAVA_OPTS "-Djava.awt.headless=true -Xmx1024m"
             "JSP_COMPILER" "javac")))))
  (is (= (stevedore/do-script
          (remote-file*
           {}
           "/etc/tomcat/tomcat6"
           :owner "root:root"
           :mode 644
           :content "JAVA_OPTS=\"-Djava.awt.headless=true\""))
         (first
          (build-actions/build-actions
           {:server {:image {:os-family :ubuntu}}}
           (default/write "/etc/tomcat/tomcat6"
             :JAVA_OPTS "-Djava.awt.headless=true"))))))
