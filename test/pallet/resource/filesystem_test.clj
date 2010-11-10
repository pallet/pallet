(ns pallet.resource.filesystem-test
  (:use clojure.test)
  (:require
   [pallet.resource.directory :as directory]
   [pallet.resource.filesystem :as filesystem]
   [pallet.resource :as resource]
   [pallet.resource.exec-script :as exec-script]
   [pallet.stevedore :as stevedore]
   [pallet.test-utils :as test-utils]))

(deftest make-xfs-filesytem-test
  (is (= (first
          (test-utils/build-resources
           []
           (exec-script/exec-checked-script
            "Format /dev/a as XFS"
            (mkfs.xfs -f "/dev/a"))))
         (first
          (test-utils/build-resources
           []
           (filesystem/make-xfs-filesytem "/dev/a"))))))

(deftest mount-test
  (is (= (first
          (test-utils/build-resources
           []
           (directory/directory "/mnt/a")
           (exec-script/exec-checked-script
            "Mount /dev/a at /mnt/a"
            ("mount " "/dev/a" (quoted "/mnt/a")))))
         (first
          (test-utils/build-resources
           []
           (filesystem/mount "/dev/a" "/mnt/a"))))))
