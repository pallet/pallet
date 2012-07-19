(ns pallet.crate.etc-default-test
 (:use
  clojure.test
  [pallet.action :only [action-fn]]
  [pallet.actions :only [remote-file]]
  [pallet.actions-impl :only [remote-file-action]]
  [pallet.build-actions :only [build-actions]]
  [pallet.common.logging.logutils :only [logging-threshold-fixture]]
  [pallet.monad :only [phase-pipeline]]
  pallet.test-utils)
 (:require
  [pallet.crate.etc-default :as default]
  [pallet.stevedore :as stevedore]))

(use-fixtures :once
              with-ubuntu-script-template with-bash-script-language
              with-null-defining-context
              (logging-threshold-fixture))

(def remote-file* (action-fn remote-file-action :direct))

(deftest default-test
  (is (=
       (first
        (build-actions {:server {:image {:os-family :ubuntu}}}
          (phase-pipeline write {}
            (remote-file
             "/etc/default/tomcat6"
             :owner "root:root"
             :mode 644
             :content
             "JAVA_OPTS=\"-Djava.awt.headless=true -Xmx1024m\"\nJSP_COMPILER=\"javac\""))))
       (first
        (build-actions {:server {:image {:os-family :ubuntu}}}
          (default/write "tomcat6"
            :JAVA_OPTS "-Djava.awt.headless=true -Xmx1024m"
            "JSP_COMPILER" "javac")))))
  (is (= (first
          (build-actions {:server {:image {:os-family :ubuntu}}}
            (phase-pipeline write {}
              (remote-file
               "/etc/tomcat/tomcat6"
               :owner "root:root"
               :mode 644
               :content "JAVA_OPTS=\"-Djava.awt.headless=true\""))))
         (first
          (build-actions {:server {:image {:os-family :ubuntu}}}
            (default/write "/etc/tomcat/tomcat6"
              :JAVA_OPTS "-Djava.awt.headless=true"))))))
