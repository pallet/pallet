(ns pallet.crate.crontab-test
  (:use pallet.crate.crontab)
  (:require
   [pallet.live-test :as live-test])
  (:use
   clojure.test
   pallet.test-utils
   [pallet.actions :only [exec-checked-script remote-file remote-file-content]]
   [pallet.core :only [lift]]
   [pallet.build-actions :only [build-actions]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.monad :only [let-s]]
   [pallet.phase :only [plan-fn]]
   [pallet.script.lib :only [user-home]]
   [pallet.session :only [admin-user]]
   [pallet.stevedore :only [script]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest user-crontab-test
  (is (= (first
          (build-actions
              {:phase-context "user-crontabs: create-user-crontab"}
            (remote-file
             "$(getent passwd fred | cut -d: -f6)/crontab.in"
             :content "contents" :owner "fred" :mode "0600")
            (exec-checked-script
             "Load crontab"
             ("crontab -u fred"
              "$(getent passwd fred | cut -d: -f6)/crontab.in\n"))))
         (first
          (build-actions {}
            (user-settings "fred" {:content "contents"})
            (user-crontabs))))))

(deftest system-crontab-test
  (is (= (first
          (build-actions
              {:phase-context "system-crontabs: create-system-crontab"}
            (remote-file
             "/etc/cron.d/fred"
             :content "contents" :owner "root" :group "root" :mode "0644")))
         (first
          (build-actions {}
            (system-settings "fred" {:content "contents"})
            (system-crontabs))))))

(deftest live-test
  (live-test/test-for
   [image live-test/*images*]
   (live-test/test-nodes
    [compute node-map node-types]
    {:crontab
     (merge
      with-crontab
      {:image image
       :count 1
       :phases
       {:settings (plan-fn
                    [user (admin-user)]
                    (user-settings (:username user) {:contents "fred"}))
        :verify (let-s
                  [user (admin-user)]
                  (do
                    (is (= "fred"
                           (remote-file-content
                            (str
                             (script (~user-home ~(:username user)))
                             "/crontab.in")))
                        "Remote file matches")))}})}
    (lift (:crontab node-types)
          :phase [:verify]
          :compute compute))))
