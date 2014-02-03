(ns -init
  "Initialise tests"
  (:require
   [clojure.test :refer :all]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions :refer [directory exec-checked-script]]
   [pallet.api :refer [group-spec lift plan-fn]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.plan :refer [phase-errors throw-phase-errors]]
   [pallet.crate :refer [admin-user]]
   [pallet.script.lib
    :refer [chown file mkdir path-owner state-root user-home]]
   [pallet.stevedore :refer [fragment]]
   [pallet.test-utils :refer [make-localhost-compute]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest initialise
  (testing "Initialise the /var/lib/pallet tree"
    (let [compute (make-localhost-compute :group-name "local")
          op (lift
              (group-spec "local")
              :phase (plan-fn
                         (with-action-options {:script-prefix :sudo
                                               :script-env-fwd [:TMPDIR]}
                           ;; (exec-checked-script
                           ;;  (str "Ensure " (state-root) " exists")
                           ;;  (if-not (directory? ~(state-root))
                           ;;    (do
                           ;;      (mkdir -p ~(state-root)))))
                           (directory
                            (fragment (file (state-root) "pallet")))
                           (directory
                            (fragment (file (state-root) "pallet"
                                            (user-home ~(:username (admin-user)))))
                            :owner (:username (admin-user))
                            :recursive false)
                           (exec-checked-script
                            "Ensure TMPDIR"
                            (if (&& (directory? @TMPDIR)
                                    (== @(path-owner @TMPDIR)
                                        ~(:username (admin-user))))
                              (do
                                (set! tpath (file (state-root) "pallet" @TMPDIR))
                                (mkdir @tpath :path true)
                                (chown ~(:username (admin-user)) @tpath))))))
              :compute compute
              :async true)
          session @op]
      (is (not (failed? op)))
      (is (nil? (phase-errors @op))))))
