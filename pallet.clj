;;; Pallet project configuration file

;;; By default, the pallet.api and pallet.crate namespaces are already referred.
;;; The pallet.crate.automated-admin-user/automated-admin-user us also referred.

(require
 '[pallet.test-specs
   :refer [remote-directory-test remote-directory-relative-test
           remote-file-test rsync-test
           operations-test rolling-lift-test
           partitioning-test exec-meta-test]]
 '[pallet.crate.initd-test :refer [initd-test-spec]]
 '[pallet.crate.nohup-test :refer [nohup-test-spec]]
 '[pallet.crate.automated-admin-user-test :refer [create-admin-test-spec]])

(defproject pallet
  :provider {:vmfest
             {:variants
              [{:node-spec
                {:image {:os-family :ubuntu :os-version-matches "12.04"
                         :os-64-bit true}}
                :group-suffix "u1204"
                :selectors #{:default}}]}}

  :groups [remote-directory-test remote-file-test remote-directory-relative-test
           rsync-test
           operations-test rolling-lift-test partitioning-test exec-meta-test
           (group-spec "initd-test"
             :extends [with-automated-admin-user
                       initd-test-spec]
             :roles #{:live-test :default :initd})
           (group-spec "nohup-test"
             :extends [with-automated-admin-user
                       nohup-test-spec]
             :roles #{:live-test :default :nohup})
           (group-spec "create-admin-test"
             :extends [create-admin-test-spec]
             :roles #{:live-test :default :create-admin})])
