(ns pallet.action.filesystem
  "Filesystem action"
  (:require
   [pallet.action.directory :as directory]
   [pallet.action.exec-script :as exec-script]
   [clojure.string :as string])
  (:use
   pallet.thread-expr))

(defn make-xfs-filesytem
  "Format a device as an XFS filesystem."
  [session device]
  (-> session
      (exec-script/exec-checked-script
       (format "Format %s as XFS" device)
       (mkfs.xfs -f ~device))))

(defmulti format-mount-option
  (fn [[key value]] (class value)))

(defmethod format-mount-option :default
  [[key value]]
  (format "%s=%s" (name key) value))

(defmethod format-mount-option java.lang.Boolean
  [[key value]]
  (when value
    (format "%s" (name key))))

(defn- mount-cmd-options [options]
  (let [option-string (string/join ","
                       (filter identity (map format-mount-option options)))]
    (if (string/blank? option-string)
      ""
      (str "-o " option-string))))

(defn mount
  "Mount a device."
  [session device mount-point
   & {:keys [fs-type device-type automount no-automount dump-frequency
             boot-check-pass]
      :or {dump-frequency 0 boot-check-pass 0}
      :as options}]
  (->
   session
   (directory/directory mount-point)
   (exec-script/exec-checked-script
    (format "Mount %s at %s" device mount-point)
    (if-not @(mountpoint -q ~mount-point)
      (mount ~(if fs-type (str "-t " fs-type) "")
             ~(mount-cmd-options
               (dissoc options :device-type :dump-frequency :boot-check-pass
                       :fs-type))
             ~device (quoted ~mount-point))))))
