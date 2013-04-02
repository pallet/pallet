(ns pallet.local.execute-test
  (:require
   [clojure.test :refer [deftest is]]
   [pallet.local.execute :refer :all]
   [pallet.script :refer [with-script-context]]
   [pallet.stevedore :refer [with-script-language]]))

(deftest build-code-test
  (is (= {:execv ["/usr/bin/env" "/bin/bash" "tmpf"]}
         (with-script-language :pallet.stevedore.bash/bash
           (with-script-context [:ubuntu]
             (build-code {} {} (java.io.File. "tmpf"))))))
  (is (= {:execv ["/usr/bin/sudo" "-n" "/usr/bin/env" "/bin/bash" "tmpf"]}
         (with-script-language :pallet.stevedore.bash/bash
           (with-script-context [:ubuntu]
             (build-code {} {:script-prefix :sudo} (java.io.File. "tmpf")))))))
