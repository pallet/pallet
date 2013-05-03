(ns pallet.script-builder-test
  (:require
   [clojure.test :refer :all]
   [pallet.common.logging.logutils :as logutils]
   [pallet.script :as script]
   [pallet.script-builder :refer [build-code prolog sudo-cmd-for]]
   [pallet.test-utils :as test-utils]
   [pallet.test-utils :refer [remove-source-line-comments]]))

(use-fixtures :once (logutils/logging-threshold-fixture))

(use-fixtures
 :each
 test-utils/with-bash-script-language)

(deftest prolog-test
  (script/with-script-context [:ubuntu]
    (is (= "#!/usr/bin/env bash\n" (prolog)))))

(deftest sudo-cmd-for-test
  (script/with-script-context [:ubuntu]
    (let [no-pw "/usr/bin/sudo -n"
          pw "echo 'fred' | /usr/bin/sudo -S"
          no-sudo nil]
      (is (= no-pw
             (remove-source-line-comments (sudo-cmd-for {:username "fred"}))))
      (is (= pw (sudo-cmd-for {:username "fred" :sudo-password "fred"})))
      (is (= no-pw
             (remove-source-line-comments
              (sudo-cmd-for
               {:username "fred" :password "fred" :sudo-password false}))))
      (is (= no-sudo (sudo-cmd-for {:username "root"})))
      (is (= no-sudo (sudo-cmd-for {:username "fred" :no-sudo true})))))
  (script/with-script-context [:centos-5.3]
    (let [no-pw "/usr/bin/sudo"
          pw "echo 'fred' | /usr/bin/sudo -S"
          no-sudo nil]
      (is (= no-pw
             (remove-source-line-comments (sudo-cmd-for {:username "fred"}))))
      (is (= pw (sudo-cmd-for {:username "fred" :sudo-password "fred"})))
      (is (= no-pw
             (remove-source-line-comments
              (sudo-cmd-for
               {:username "fred" :password "fred" :sudo-password false}))))
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
