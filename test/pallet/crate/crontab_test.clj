(ns pallet.crate.crontab-test
  (:use pallet.crate.crontab)
  (:require
   [pallet.live-test :as live-test]
   [pallet.test-utils :refer [no-location-info]])
  (:use
   clojure.test
   [clojure.string :only [trim]]
   [pallet.action :only [clj-action]]
   [pallet.actions :only [exec-checked-script remote-file remote-file-content]]
   [pallet.algo.fsmop :only [operate complete?]]
   [pallet.api :only [plan-fn]]
   [pallet.build-actions :only [build-actions]]
   [pallet.core.operations :only [lift]]
   [pallet.crate :only [admin-user]]
   [pallet.crate.automated-admin-user :only [automated-admin-user]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.environment :only [get-for]]
   [pallet.live-test :only [test-for test-nodes images]]
   [pallet.node-value :only [node-value]]
   [pallet.script.lib :only [user-home]]
   [pallet.stevedore :only [script]]))

(use-fixtures :once (logging-threshold-fixture) no-location-info)

(deftest user-crontab-test
  (is (script-no-comment=
       (first
        (build-actions
            {:phase-context "user-crontabs: create-user-crontab"}
          (remote-file
           "$(getent passwd fred | cut -d: -f6)/crontab.in"
           :content "contents" :owner "fred" :mode "0600")
          (exec-checked-script
           "Load crontab"
           ("crontab -u fred"
            "$(getent passwd fred | cut -d: -f6)/crontab.in"))))
       (first
        (build-actions {}
          (user-settings "fred" {:content "contents"})
          (user-crontabs))))))

(deftest system-crontab-test
  (is (script-no-comment=
       (first
        (build-actions {:phase-context "system-crontabs: create-system-crontab"}
          (remote-file
           "/etc/cron.d/fred"
           :content "contents" :owner "root" :group "root" :mode "0644")))
       (first
        (build-actions {}
          (system-settings "fred" {:content "contents"})
          (system-crontabs))))))

(def crontab-for-test
  "0 1 1 1 1 ls > /dev/null")

(deftest live-test
  (test-for [image (images)]
    (test-nodes [compute node-map node-types]
        {:crontab
         (merge
          with-crontab
          {:image image
           :count 1
           :phases
           {:settings (plan-fn
                        (let [user (admin-user)]
                          (user-settings
                           (:username user) {:content crontab-for-test})))
            :bootstrap (automated-admin-user)
            :configure (plan-fn
                         (system-crontabs :action :create)
                         (user-crontabs :action :create))
            :verify (plan-fn
                      (let [user (admin-user)
                            fcontent (remote-file-content
                                      (str
                                       (script (~user-home ~(:username user)))
                                       "/crontab.in"))
                            v ((clj-action [session]
                                           (let [f (get-for session [:file-checker])]
                                             (f session fcontent))
                                           [nil session]))]
                        v))}})}
      (let [op (operate
                (lift [(:crontab node-types)] nil [:verify] compute
                      {:file-checker
                       (bound-fn [session fcontent]
                         (let [content (fcontent session)]
                           (is (= crontab-for-test (trim content))
                               "Remote file matches")))}
                      {}))]
        @op
        (is (complete? op))))))
