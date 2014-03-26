(ns -init
  "Initialise tests"
  (:require
   [clojure.test :refer :all]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions :refer [directory exec-checked-script]]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.group :refer [group-spec lift phase-errors throw-phase-errors]]
   [pallet.log :refer [default-log-config]]
   [pallet.plan :refer [plan-fn]]
   [pallet.script.lib
    :refer [chgrp chmod chown file mkdir path-group path-mode path-owner
            state-root tmp-dir user-home]]
   [pallet.session :refer [target]]
   [pallet.stevedore :refer [fragment]]
   [pallet.target-info :refer [admin-user]]
   [pallet.test-utils :refer [make-localhost-compute]]))

(use-fixtures :once (logging-threshold-fixture))

(default-log-config)

(deftest initialise
  (testing "Initialise the /var/lib/pallet tree"
    (let [compute (make-localhost-compute :group-name "local")
          session (lift
                   (group-spec "local" {})
                   :phase (plan-fn [session]
                            (let [user (admin-user session)]
                              (with-action-options session
                                {:script-prefix :sudo
                                 :script-env-fwd [:TMP :TEMP :TMPDIR]}
                                (directory
                                 session
                                 (fragment (file (state-root) "pallet")))
                                (directory
                                 session
                                 (fragment
                                  (file (state-root) "pallet"
                                        (user-home ~(:username user))))
                                 {:owner (:username user)
                                  :recursive false})
                                ;; tmp needs handling specially, as it is not
                                ;; necessarily root owned, while the parent dir
                                ;; definitely is.
                                (exec-checked-script
                                 session
                                 "Ensure tmp"
                                 (if (directory? (tmp-dir))
                                   (do
                                     (set! tpath
                                           (file (state-root)
                                                 "pallet" (tmp-dir)))
                                     (mkdir @tpath :path true)
                                     (if-not (== @(path-group (tmp-dir))
                                                 @(path-group $tpath))
                                       (chgrp @(path-group (tmp-dir)) @tpath))
                                     (if-not (== @(path-mode (tmp-dir))
                                                 @(path-mode $tpath))
                                       (chmod @(path-mode (tmp-dir)) @tpath))
                                     (if-not (== @(path-owner (tmp-dir))
                                                 @(path-owner $tpath))
                                       (chown @(path-owner (tmp-dir))
                                              @tpath))))))))
                   :compute compute)]
      (is (nil? (phase-errors session))))))
