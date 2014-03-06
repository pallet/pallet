(ns pallet.crate.filesystem-test
  (:require
   [clojure.string :as string]
   [clojure.test :refer :all]
   [pallet.actions :refer [directory exec-checked-script]]
   [pallet.build-actions :refer [build-plan]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.crate.filesystem :as filesystem]
   [pallet.plan :refer [plan-context]]
   [pallet.test-utils
    :refer [no-location-info no-source-line-comments]]))

(use-fixtures :once
  (logging-threshold-fixture)
  no-location-info
  no-source-line-comments)

(deftest make-xfs-filesytem-test
  (is (=
       (build-plan [session {}]
         (plan-context make-xfs-filesytem {}
           (exec-checked-script
            session
            "Format /dev/a as XFS"
            ("mkfs.xfs" -f "/dev/a"))))
       (build-plan [session {}]
         (filesystem/make-xfs-filesytem session "/dev/a")))))

(deftest mount-test
  (is (=
       (build-plan [session {}]
         (plan-context mount {}
           (directory session "/mnt/a")
           (exec-checked-script
            session
            "Mount /dev/a at /mnt/a"
            (if-not @("mountpoint" -q "/mnt/a")
              ("mount" "/dev/a" (quoted "/mnt/a"))))))
       (build-plan [session {}]
         (filesystem/mount session "/dev/a" "/mnt/a"))))
  (is (=
       (build-plan [session {}]
         (plan-context mount {}
           (directory session "/mnt/a")
           (exec-checked-script
            session
            "Mount /dev/a at /mnt/a"
            (if-not @("mountpoint" -q "/mnt/a")
              ("mount" -t "vboxsf" -o
               ~(string/join ","
                             (for [[k v] {:uid "user" :gid "user"}]
                               (str (name k) "=" v)))
               "/dev/a" (quoted "/mnt/a"))))))
       (build-plan [session {}]
         (filesystem/mount session
                           "/dev/a" "/mnt/a" :fs-type "vboxsf"
                           :uid "user" :gid "user")))))
