(ns pallet.crate.filesystem-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [directory exec-checked-script]]
   [pallet.build-actions :as build-actions]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.crate.filesystem :as filesystem]))

(use-fixtures :once (logging-threshold-fixture))

(deftest make-xfs-filesytem-test
  (is (script-no-comment=
       (first
        (build-actions/build-actions
            [session {:phase-context "make-xfs-filesytem"}]
          (exec-checked-script
           session
           "Format /dev/a as XFS"
           ("mkfs.xfs" -f "/dev/a"))))
       (first
        (build-actions/build-actions [session {}]
          (filesystem/make-xfs-filesytem session "/dev/a"))))))

(deftest mount-test
  (is (script-no-comment=
       (first
        (build-actions/build-actions
            [session {:phase-context "mount"}]
          (directory session "/mnt/a")
          (exec-checked-script
           session
           "Mount /dev/a at /mnt/a"
           (if-not @("mountpoint" -q "/mnt/a")
             ("mount" "/dev/a" (quoted "/mnt/a"))))))
       (first
        (build-actions/build-actions [session {}]
          (filesystem/mount session "/dev/a" "/mnt/a")))))
  (is (script-no-comment=
       (first
        (build-actions/build-actions [session {:phase-context "mount"}]
          (directory session "/mnt/a")
          (exec-checked-script
           session
           "Mount /dev/a at /mnt/a"
           (if-not @("mountpoint" -q "/mnt/a")
             ("mount" -t "vboxsf" -o "gid=user,uid=user"
              "/dev/a" (quoted "/mnt/a"))))))
       (first
        (build-actions/build-actions [session {}]
          (filesystem/mount session
                            "/dev/a" "/mnt/a" :fs-type "vboxsf"
                            :uid "user" :gid "user"))))))
