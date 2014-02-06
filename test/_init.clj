(ns -init
  "Initialise tests"
  (:require
   [clojure.test :refer :all]
   [pallet.action :refer [with-action-options]]
   [pallet.actions :refer [directory exec-checked-script]]
   [pallet.algo.fsmop :refer [failed?]]
   [pallet.api :refer [group-spec lift plan-fn]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.api :refer [phase-errors]]
   [pallet.crate :refer [admin-user]]
   [pallet.script.lib
    :refer [chown file mkdir path-owner state-root tmp-dir user-home]]
   [pallet.stevedore :refer [fragment]]
   [pallet.test-utils :refer [make-localhost-compute]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest initialise
  (let [compute (make-localhost-compute :group-name "local")
        op (lift
            (group-spec "local")
            :phase (plan-fn
                    (with-action-options {:script-prefix :sudo
                                          :script-env-fwd [:TMP :TEMP :TMPDIR]}
                      (directory
                       (fragment (file (state-root) "pallet")))
                      (directory
                       (fragment (file (state-root) "pallet"
                                       (user-home ~(:username (admin-user)))))
                       :owner (:username (admin-user))
                       :recursive false)
                      (exec-checked-script
                       "Ensure tmp"
                       (if (directory? (tmp-dir))
                         (do
                           (set! tpath (file (state-root) "pallet" @TMPDIR))
                           (mkdir @tpath :path true)
                           (if (== @(path-owner @TMPDIR)
                                   ~(:username (admin-user)))
                             (chown ~(:username (admin-user)) @tpath)))))))
            :compute compute
            :async true)
        session @op]
    (is (not (failed? op)))
    (is (nil? (phase-errors @op)))))
