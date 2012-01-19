(ns pallet.actions.direct.retry-test
  (:require
   [pallet.action :as action]
   [pallet.stevedore :as stevedore])
  (:use
   clojure.test
   [pallet.actions :only [retry-until]]
   [pallet.build-actions :only [build-actions]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest retry-test
  (is (= "echo \"Wait for x...\"\n{ { let x=0 || true; } && while [ ! -e abc ]; do\nlet x=(x + 1)\nif [ \\( \"5\" == \"${x}\" \\) ]; then\necho Timed out waiting for x >&2\nexit 1\nfi\necho Waiting for x\nsleep 2\ndone; } || { echo \"Wait for x\" failed; exit 1; } >&2 \necho \"...done\"\n"
         (first (build-actions {}
                  (retry-until
                   {:service-name "x"}
                   (stevedore/script (file-exists? abc))))))))
