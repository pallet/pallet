(ns pallet.action.filesystem-test
  (:use
   clojure.test
   [pallet.common.logging.logutils :only [logging-threshold-fixture]])
  (:require
   [pallet.action.directory :as directory]
   [pallet.action.filesystem :as filesystem]
   [pallet.action.exec-script :as exec-script]
   [pallet.build-actions :as build-actions]
   [pallet.stevedore :as stevedore]))

(use-fixtures :once (logging-threshold-fixture))

(deftest make-xfs-filesytem-test
  (is (= (first
          (pallet.context/with-phase-context
            {:kw :generate-key :msg "make-xfs-filesytem"}
            (build-actions/build-actions
             {}
             (exec-script/exec-checked-script
              "Format /dev/a as XFS"
              (mkfs.xfs -f "/dev/a")))))
         (first
          (build-actions/build-actions
           {}
           (filesystem/make-xfs-filesytem "/dev/a"))))))

(deftest mount-test
  (is (= (first
          (pallet.context/with-phase-context
            {:kw :generate-key :msg "mount"}
            (build-actions/build-actions
             {}
             (directory/directory "/mnt/a")
             (exec-script/exec-checked-script
              "Mount /dev/a at /mnt/a"
              (if-not @(mountpoint -q "/mnt/a")
                (mount "/dev/a" (quoted "/mnt/a")))))))
         (first
          (build-actions/build-actions
           {}
           (filesystem/mount "/dev/a" "/mnt/a")))))
  (is (= (first
          (pallet.context/with-phase-context
            {:kw :generate-key :msg "mount"}
            (build-actions/build-actions
             {}
             (directory/directory "/mnt/a")
             (exec-script/exec-checked-script
              "Mount /dev/a at /mnt/a"
              (if-not @(mountpoint -q "/mnt/a")
                (mount -t "vboxsf" -o "gid=user,uid=user"
                       "/dev/a" (quoted "/mnt/a")))))))
         (first
          (build-actions/build-actions
           {}
           (filesystem/mount "/dev/a" "/mnt/a" :fs-type "vboxsf"
                             :uid "user" :gid "user"))))))
