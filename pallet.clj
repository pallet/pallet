;;; Pallet project configuration file

;;; By default, the pallet.api and pallet.crate namespaces are already referred.
;;; The pallet.crate.automated-admin-user/automated-admin-user us also referred.

(require
 '[pallet.test-specs
   :refer [remote-file-test rsync-test operations-test rolling-lift-test]]
 '[pallet.crate.initd-test :refer [initd-test-spec]]
 '[pallet.crate.nohup-test :refer [nohup-test-spec]])

(defproject pallet
  :provider {:vmfest
             {:variants
              [{:node-spec
                {:image {:os-family :ubuntu :os-version-matches "12.04"
                         :os-64-bit true}}
                :group-suffix "u1204"
                :selectors #{:default}}]}}

  :groups [remote-file-test rsync-test
           operations-test rolling-lift-test
           (group-spec "initd-test"
             :extends [with-automated-admin-user
                       initd-test-spec]
             :roles #{:live-test :default :initd})
           (group-spec "nohup-test"
             :extends [with-automated-admin-user
                       nohup-test-spec]
             :roles #{:live-test :default :nohup})])
