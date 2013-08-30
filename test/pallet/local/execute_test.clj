(ns pallet.local.execute-test
  (:require
   [clojure.test :refer [deftest is]]
   [pallet.local.execute :refer :all]
   [pallet.script :refer [with-script-context]]
   [pallet.stevedore :refer [with-script-language]]))

(deftest build-code-test
  (is (= {:env-cmd "/usr/bin/env"
          :env-fwd [:SSH_AUTH_SOCK]
          :env nil
          :prefix nil
          :execv ["/bin/bash" "tmpf"]}
         (with-script-language :pallet.stevedore.bash/bash
           (with-script-context [:ubuntu]
             (build-code {} {} (java.io.File. "tmpf"))))))
  (is (= {:prefix ["/usr/bin/sudo" "-n"]
          :env-fwd [:SSH_AUTH_SOCK]
          :env-cmd "/usr/bin/env"
          :env nil
          :execv ["/bin/bash" "tmpf"]}
         (with-script-language :pallet.stevedore.bash/bash
           (with-script-context [:ubuntu]
             (build-code {} {:script-prefix :sudo} (java.io.File. "tmpf")))))))
