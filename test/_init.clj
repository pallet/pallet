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
    :refer [chgrp chmod chown file mkdir path-group path-mode path-owner
            state-root tmp-dir user-home]]
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
                                               :script-env-fwd [:TMP :TEMP :TMPDIR]}
                           (directory
                            (fragment (file (state-root) "pallet")))
                           (directory
                            (fragment (file (state-root) "pallet"
                                            (user-home ~(:username (admin-user)))))
                            :owner (:username (admin-user))
                            :recursive false)
                           ;; tmp needs handling specially, as it is not
                           ;; necessarily root owned, while the parent dir
                           ;; definitely is.
                           (exec-checked-script
                            "Ensure tmp"
                            (if (directory? (tmp-dir))
                              (do
                                (set! tpath (file (state-root) "pallet" (tmp-dir)))
                                (mkdir @tpath :path true)
                                (if-not (== @(path-group (tmp-dir))
                                            @(path-group $tpath))
                                  (chgrp @(path-group (tmp-dir)) @tpath))
                                (if-not (== @(path-mode (tmp-dir))
                                            @(path-mode $tpath))
                                  (chmod @(path-mode (tmp-dir)) @tpath))
                                (if-not (== @(path-owner (tmp-dir))
                                            @(path-owner $tpath))
                                  (chown @(path-owner (tmp-dir)) @tpath)))))))
              :compute compute
              :async true)
          session @op]
      (is (not (failed? op)))
      (is (nil? (phase-errors @op))))))
