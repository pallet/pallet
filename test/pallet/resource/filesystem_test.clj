(ns pallet.resource.filesystem-test
  (:use clojure.test)
  (:require
   [pallet.resource.directory :as directory]
   [pallet.resource.filesystem :as filesystem]
   [pallet.resource.exec-script :as exec-script]
   [pallet.stevedore :as stevedore]
   [pallet.build-actions :as build-actions]))

(deftest make-xfs-filesytem-test
  (is (= (first
          (build-actions/build-actions
           []
           (exec-script/exec-checked-script
            "Format /dev/a as XFS"
            (mkfs.xfs -f "/dev/a"))))
         (first
          (build-actions/build-actions
           []
           (filesystem/make-xfs-filesytem "/dev/a"))))))

(deftest mount-test
  (is (= (first
          (build-actions/build-actions
           []
           (directory/directory "/mnt/a")
           (exec-script/exec-checked-script
            "Mount /dev/a at /mnt/a"
            (mount "/dev/a" (quoted "/mnt/a")))))
         (first
          (build-actions/build-actions
           []
           (filesystem/mount "/dev/a" "/mnt/a"))))))
