(ns pallet.script-builder-test
  (:use pallet.script-builder)
  (:use clojure.test
        clojure.tools.logging)
  (:require
   [pallet.common.logging.logutils :as logutils]
   [pallet.test-utils :as test-utils]
   [pallet.script :as script]))

(use-fixtures :once (logutils/logging-threshold-fixture))

(use-fixtures
 :each
 test-utils/with-bash-script-language)

(deftest sudo-cmd-for-test
  (script/with-script-context [:ubuntu]
    (let [no-pw "/usr/bin/sudo -n"
          pw "echo 'fred' | /usr/bin/sudo -S"
          no-sudo nil]
      (is (= no-pw (sudo-cmd-for {:username "fred"})))
      (is (= pw (sudo-cmd-for {:username "fred" :sudo-password "fred"})))
      (is (= no-pw
             (sudo-cmd-for
              {:username "fred" :password "fred" :sudo-password false})))
      (is (= no-sudo (sudo-cmd-for {:username "root"})))
      (is (= no-sudo (sudo-cmd-for {:username "fred" :no-sudo true})))))
  (script/with-script-context [:centos-5.3]
    (let [no-pw "/usr/bin/sudo"
          pw "echo 'fred' | /usr/bin/sudo -S"
          no-sudo nil]
      (is (= no-pw (sudo-cmd-for {:username "fred"})))
      (is (= pw (sudo-cmd-for {:username "fred" :sudo-password "fred"})))
      (is (= no-pw
             (sudo-cmd-for
              {:username "fred" :password "fred" :sudo-password false})))
      (is (= no-sudo (sudo-cmd-for {:username "root"})))
      (is (= no-sudo (sudo-cmd-for {:username "fred" :no-sudo true}))))))

(deftest build-code-test
  (script/with-script-context [:ubuntu]
    (is (= {:execv ["/usr/bin/sudo" "-n" "/usr/bin/env" "/bin/bash"]}
           (build-code {:user {}} {})))
    (is (= {:execv ["/usr/bin/env" "/bin/bash"]}
           (build-code {:user {:no-sudo true}} {})))
    (is (= {:execv ["/usr/bin/env" "/bin/bash"]}
           (build-code {:user {}} {:script-prefix :no-prefix})))
    (is (= {:execv
            ["/usr/bin/sudo" "-n" "-u" "fred" "/usr/bin/env" "/bin/bash"]}
           (build-code {:user {}} {:sudo-user "fred"})))
    (is (= {:execv
            ["/usr/bin/sudo" "-n" "-u" "fred" "/usr/bin/env" "/bin/bash"]}
           (build-code {:user {:sudo-user "fred"}} {})))))
