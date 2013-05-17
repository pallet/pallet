(ns pallet.actions.direct.retry-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [loop-until retry-until]]
   [pallet.build-actions :refer [build-actions]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.stevedore :refer [script]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest retry-test
  (is (script-no-comment=
       (first (build-actions {}
                (loop-until "x" (script (file-exists? abc)) 5 2)))
       (first (build-actions {}
                (retry-until
                 {:service-name "x"}
                 (script (file-exists? abc))))))))

;; (against-background [(around :facts (with-threshold [:warn] ?form))]
;;   (fact
;;     (first (build-actions {}
;;              (retry-until
;;               {:service-name "x"}
;;               (stevedore/script (file-exists? abc)))))
;;     => (bash "echo \"Wait for x...\"\n"
;;              "{ { let x=0 || true; } && "
;;              "while ! ( [ -e abc ] ); do\n"
;;              "let x=(x + 1)\n"
;;              "if [ \"5\" == \"${x}\" ]; then\n"
;;              "echo Timed out waiting for x >&2\nexit 1\nfi\n"
;;              "echo Waiting for x\n"
;;              "sleep 2\ndone; } || { echo \"Wait for x\" failed; exit 1; } >&2 "
;;              "\necho \"...done\"\n")))

;; (fact (+ 1 2) => 2)
