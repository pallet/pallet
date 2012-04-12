(ns pallet.action.retry-test
  (:require
   [pallet.action :as action]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.retry :as retry]
   [pallet.stevedore :as stevedore])
  (:use
   clojure.test
   pallet.build-actions
   pallet.test-utils))

(deftest retry-test
  (is (= "echo \"Wait for x...\"\n{ { let x=0 || true; } && while ! ( [ -e abc ] ); do\nlet x=(x + 1)\nif [ \"5\" == \"${x}\" ]; then\necho Timed out waiting for x >&2\nexit 1\nfi\necho Waiting for x\nsleep 2\ndone; } || { echo \"Wait for x\" failed; exit 1; } >&2 \necho \"...done\"\n"
         (first (build-actions
                 {}
                 (retry/retry-until
                  {:service-name "x"}
                  (stevedore/script (file-exists? abc))))))))
