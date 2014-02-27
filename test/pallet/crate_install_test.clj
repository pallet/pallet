(ns pallet.crate-install-test
  (:require
   [clojure.test :refer :all]
   [pallet.build-actions :refer [build-plan]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.crate-install :refer :all]
   [pallet.settings :refer [assoc-settings]]))

(use-fixtures :once
  (logging-threshold-fixture))

(deftest install-test
  (is (build-plan [session {}]
        (assoc-settings
         session
         :f {:install-strategy :packages
             :packages []})
        (install session :f nil)))
  (is (build-plan [session {}]
        (assoc-settings
         session
         :f {:install-strategy :package-source
             :package-source {:name "my-source"}
             :packages []
             :package-options {}})
        (install session :f nil)))
  (is (build-plan [session {}]
        (assoc-settings
         session
         :f {:install-strategy :rpm
             :rpm {:remote-file "http://somewhere.com/"
                   :name "xx"}})
        (install session :f nil)))
  (is (build-plan [session {}]
        (assoc-settings
         session
         :f {:install-strategy :rpm-repo
             :rpm {:remote-file "http://somewhere.com/"
                   :name "xx"}
             :packages []})
        (install session :f nil)))
  (is (build-plan [session {}]
        (assoc-settings
         session
         :f {:install-strategy :deb
             :debs {:remote-file "http://somewhere.com/"
                    :name "xx"}
             :package-source {:name "xx" :apt {:path "abc"}}
             :packages []})
        (install session :f nil))))
