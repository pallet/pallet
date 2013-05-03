(ns pallet.crate.crontab-test
  (:require
   [clojure.string :refer [trim]]
   [clojure.test :refer :all]
   [pallet.action :refer [clj-action]]
   [pallet.actions :refer [exec-checked-script remote-file remote-file-content]]
   [pallet.algo.fsmop :refer [complete? operate]]
   [pallet.api :refer [plan-fn]]
   [pallet.build-actions :refer [build-actions]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.operations :refer [lift]]
   [pallet.crate :refer [admin-user]]
   [pallet.crate.automated-admin-user :refer [automated-admin-user]]
   [pallet.crate.crontab
    :refer [system-crontabs
            system-settings
            user-crontabs
            user-settings
            with-crontab]]
   [pallet.environment :refer [get-for]]
   [pallet.live-test :refer [images test-for test-nodes]]
   [pallet.script.lib :refer [user-home]]
   [pallet.stevedore :refer [script]]
   [pallet.test-utils :refer [no-location-info]]))

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
