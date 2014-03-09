(ns pallet.crate.crontab-test
  (:require
   [clojure.string :refer [trim]]
   [clojure.test :refer :all]
   [pallet.actions :refer [exec-checked-script remote-file remote-file-content]]
   [pallet.build-actions :refer [build-plan]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.crate.automated-admin-user :refer [with-automated-admin-user]]
   [pallet.crate.crontab :as crontab
    :refer [system-crontabs
            system-settings
            user-crontabs
            user-settings]]
   [pallet.environment :refer [get-environment]]
   [pallet.group :refer [lift phase-errors]]
   [pallet.live-test :refer [images test-for test-nodes]]
   [pallet.plan :refer [plan-context plan-fn]]
   [pallet.script.lib :refer [user-home]]
   [pallet.target-info :refer [admin-user]]
   [pallet.spec :refer [extend-specs]]
   [pallet.stevedore :refer [script]]
   [pallet.test-utils :refer [no-location-info no-source-line-comments]]))

(use-fixtures :once
  (logging-threshold-fixture)
  no-location-info
  no-source-line-comments)

(deftest user-crontab-test
  (is (=
       (build-plan [session {}]
         (remote-file
          session
          "$(getent passwd fred | cut -d: -f6)/crontab.in"
          {:content "contents" :owner "fred" :mode "0600"})
         (plan-context "user-crontabs" {}
           (plan-context "create-user-crontab" {}
             (exec-checked-script
              session
              "Load crontab"
              ("crontab -u fred"
               "$(getent passwd fred | cut -d: -f6)/crontab.in")))))
       (build-plan [session {}]
         (user-settings session "fred" {:content "contents"})
         (user-crontabs session)))))

(deftest system-crontab-test
  (is (=
       (build-plan [session {}]
         (remote-file
          session
          "/etc/cron.d/fred"
          {:content "contents" :owner "root" :group "root" :mode "0644"}))
       (build-plan [session {}]
         (system-settings session "fred" {:content "contents"})
         (system-crontabs session)))))

(def crontab-for-test
  "0 1 1 1 1 ls > /dev/null")

(deftest live-test
  (test-for [image (images)]
    (test-nodes [compute node-map node-types]
        {:crontab
         (extend-specs
          {:image image
           :count 1
           :phases
           {:settings (plan-fn [session]
                        (let [user (admin-user session)]
                          (user-settings
                           session
                           (:username user) {:content crontab-for-test})))
            :configure (plan-fn [session]
                         (system-crontabs session :action :create)
                         (user-crontabs session :action :create))
            :verify (plan-fn [session]
                      (let [user (admin-user session)
                            fcontent (remote-file-content
                                      session
                                      (str
                                       (script (~user-home ~(:username user)))
                                       "/crontab.in"))
                            v (let [f (get-environment session [:file-checker])]
                                (f session fcontent))]
                        v))}}
          [with-automated-admin-user
           (crontab/server-spec {})])}
        (let [res (lift
                   [(:crontab node-types)] nil [:verify] compute
                   {:file-checker
                    (bound-fn [session fcontent]
                      (let [content (fcontent session)]
                        (is (= crontab-for-test (trim content))
                            "Remote file matches")))}
                   {})]
        (is (not (phase-errors res)))))))
