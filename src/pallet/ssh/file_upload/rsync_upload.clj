(ns pallet.ssh.file-upload.rsync-upload
  "Implementation of file upload using rsync."
  (:require
   [pallet.actions.direct.rsync :refer [rsync-command]]
   [pallet.local.execute :refer [local-checked-script]]
   [pallet.core.session :refer [effective-username]]
   [pallet.core.file-upload.protocols :refer [FileUpload]]))

(defn target
  "Return the target file path for the upload"
  [target-path]
  (str target-path ".new"))

(defn rsync-upload-file
  [local-path target-path file-options action-options]
  (local-checked-script
   "rsync file to target"
   (rsync-command
    local-path target-path
    username
    address port (merge {:chmod "600"} options))))

(defrecord RsyncUpload []
  FileUpload
  (upload-file-path [_ target-path action-options]
    (target target-path))
  (upload-file
    [_ session local-path target-path action-options]
    (let [eff (effective-username session)]
      (rsync-upload-file
       local-path (target target-path) file-options action-options))))
