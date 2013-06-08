(ns pallet.actions.direct.user-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [group]]
   [pallet.actions.direct.user :refer [user*]]
   [pallet.build-actions :refer [build-actions]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :refer [script]]
   [pallet.test-utils :as test-utils]))

(use-fixtures
 :once
 test-utils/with-ubuntu-script-template
 test-utils/with-bash-script-language
 test-utils/with-no-source-line-comments
 (logging-threshold-fixture))

(deftest user*-create-test
  (is (script-no-comment=
       (script
        (if-not (~lib/user-exists? "user1")
          (~lib/create-user "user1" {:shell "/bin/bash"})))
       (user* {} "user1" :action :create :shell :bash))))

(deftest user*-modify-test
  (is (script-no-comment=
       (script
        (if (~lib/user-exists? "user1")
          ":"
          (~lib/create-user "user1" {})))
       (user* {} "user1" :action :manage)))
  (is (script-no-comment=
       (script
        (if (~lib/user-exists? "user1")
          (~lib/modify-user "user1" {:p "p"})
          (~lib/create-user "user1" {:p "p"})))
       (pallet.script/with-script-context [:fedora]
         (user* {} "user1" :action :manage :password "p")))))

(deftest user*-lock-test
  (is (script-no-comment=
       (script
        (if (~lib/user-exists? "user1")
          (~lib/lock-user "user1")))
       (user* {} "user1" :action :lock))))

(deftest user*-unlock-test
  (is (script-no-comment=
       (script
        (if (~lib/user-exists? "user1")
          (~lib/unlock-user "user1")))
       (user* {} "user1" :action :unlock))))

(deftest user*-remove-test
  (is (script-no-comment=
       (script
        (if (~lib/user-exists? "user1")
          (~lib/remove-user "user1" {})))
       (user* {} "user1" :action :remove))))

(deftest group-create-test
  (is (script-no-comment=
       (script
        (if-not (~lib/group-exists? "group11")
          (~lib/create-group "group11" {})))
       (first (build-actions {}
                (group "group11" :action :create)))))
  (testing "system on rh"
    (is (script-no-comment=
         (script
          (if-not (~lib/group-exists? "group11")
            (~lib/create-group "group11" {:r true})))
         (first (build-actions
                    {:server {:image {:os-family :centos}}}
                  (group "group11" :action :create :system true)))))))
