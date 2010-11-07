(ns pallet.resource.filesystem
  "Filesystem resource"
  (:require
   [pallet.resource.directory :as directory]
   [pallet.resource.exec-script :as exec-script]
   [clojure.string :as string])
  (:use
   pallet.thread-expr))

(defn make-xfs-filesytem
  "Format a device as an XFS filesystem."
  [request device]
  (-> request
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
  [request device mount-point
   & {:keys [device-type automount no-automount dump-frequency boot-check-pass]
      :or {dump-frequency 0 boot-check-pass 0}
      :as options}]
  (->
   request
   (directory/directory mount-point)
   (exec-script/exec-checked-script
    (format "Mount %s at %s" device mount-point)
    (mount ~(mount-cmd-options
             (dissoc options :device-type :dump-frequency :boot-check-pass))
           ~device (quoted ~mount-point)))))
