(ns pallet.crate.filesystem-test
  (:use
   clojure.test
   [pallet.actions :only [directory exec-checked-script]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]])
  (:require
   [pallet.crate.filesystem :as filesystem]
   [pallet.build-actions :as build-actions]
   [pallet.stevedore :as stevedore]
   [pallet.test-utils :refer [no-location-info]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest make-xfs-filesytem-test
  (is (script-no-comment=
       (first
        (build-actions/build-actions
         {:phase-context "make-xfs-filesytem"}
         (exec-checked-script
          "Format /dev/a as XFS"
          ("mkfs.xfs" -f "/dev/a"))))
       (first
        (build-actions/build-actions
         {}
         (filesystem/make-xfs-filesytem "/dev/a"))))))

(deftest mount-test
  (is (script-no-comment=
       (first
        (build-actions/build-actions
         {:phase-context "mount"}
         (directory "/mnt/a")
         (exec-checked-script
          "Mount /dev/a at /mnt/a"
          (if-not @(mountpoint -q "/mnt/a")
            (mount "/dev/a" (quoted "/mnt/a"))))))
       (first
        (build-actions/build-actions
         {}
         (filesystem/mount "/dev/a" "/mnt/a")))))
  (is (script-no-comment=
       (first
        (build-actions/build-actions
         {:phase-context "mount"}
         (directory "/mnt/a")
         (exec-checked-script
          "Mount /dev/a at /mnt/a"
          (if-not @(mountpoint -q "/mnt/a")
            (mount -t "vboxsf" -o "gid=user,uid=user"
                   "/dev/a" (quoted "/mnt/a"))))))
       (first
        (build-actions/build-actions
         {}
         (filesystem/mount "/dev/a" "/mnt/a" :fs-type "vboxsf"
                           :uid "user" :gid "user"))))))
