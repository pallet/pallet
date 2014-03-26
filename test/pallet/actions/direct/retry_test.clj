(ns pallet.actions.direct.retry-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [loop-until retry-until]]
   [pallet.build-actions :refer [build-plan]]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.stevedore :refer [script]]
   [pallet.test-utils :refer [no-location-info with-no-source-line-comments]]))

(use-fixtures :once
  (logging-threshold-fixture)
  no-location-info
  with-no-source-line-comments)

(deftest retry-test
  (is (=
       (build-plan [session {}]
         (loop-until session
                     "x"
                     (script (file-exists? abc)) 5 2))
       (build-plan [session {}]
         (retry-until
          session
          {:service-name "x"}
          (script (file-exists? abc)))))))

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
