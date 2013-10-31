(ns pallet.crate.etc-default-test
  (:require
   [clojure.test :refer :all]
   [pallet.action :refer [action-fn]]
   [pallet.actions :refer [remote-file]]
   [pallet.actions-impl :refer [remote-file-action]]
   [pallet.build-actions :refer [build-actions]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.crate :refer [phase-context]]
   [pallet.crate.etc-default :as default]
   [pallet.test-utils
    :refer [with-bash-script-language
            with-null-defining-context
            with-ubuntu-script-template]]))

(use-fixtures :once
              with-ubuntu-script-template with-bash-script-language
              with-null-defining-context
              (logging-threshold-fixture))

(def remote-file* (action-fn remote-file-action :direct))

(deftest default-test
  (is (script-no-comment=
       (first
        (build-actions {:server {:image {:os-family :ubuntu}}}
          (phase-context write {}
            (remote-file
             "/etc/default/tomcat6"
             :owner "root"
             :group "root"
             :mode 644
             :content
             "JAVA_OPTS=\"-Djava.awt.headless=true -Xmx1024m\"\nJSP_COMPILER=\"javac\""))))
       (first
        (build-actions {:server {:image {:os-family :ubuntu}}}
          (default/write "tomcat6"
            :JAVA_OPTS "-Djava.awt.headless=true -Xmx1024m"
            "JSP_COMPILER" "javac")))))
  (is (script-no-comment=
       (first
        (build-actions {:server {:image {:os-family :ubuntu}}}
          (phase-context write {}
            (remote-file
             "/etc/tomcat/tomcat6"
             :owner "root"
             :group "root"
             :mode 644
             :content "JAVA_OPTS=\"-Djava.awt.headless=true\""))))
       (first
        (build-actions {:server {:image {:os-family :ubuntu}}}
          (default/write "/etc/tomcat/tomcat6"
            :JAVA_OPTS "-Djava.awt.headless=true"))))))
