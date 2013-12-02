(ns pallet.core.file-upload.rsync-upload
  "Implementation of file upload using rsync."
  (:require
   [clojure.tools.logging :refer [debugf]]
   [pallet.actions.direct.rsync
    :refer [default-options rsync-command rsync-sudo-user]]
   [pallet.local.execute :refer [local-checked-script]]
   [pallet.core.session
    :refer [admin-user effective-username target-ip target-node]]
   [pallet.core.file-upload :refer [file-uploader]]
   [pallet.core.file-upload.protocols :refer [FileUpload]]
   [pallet.node :refer [ssh-port]]
   [pallet.ssh.file-upload.sftp-upload :refer [target]]))

(defn rsync-user [session]
  (or (rsync-sudo-user session)
      (:username (admin-user session))))

(defn rsync-upload-file
  [local-path target-path address port username options]
  (debugf "rsync-upload-file %s:%s:%s from %s"
          address port target-path local-path)
  (local-checked-script
   "rsync file to target"
   ~(rsync-command
     local-path target-path
     username
     address port
     options)))


(defrecord RsyncUpload [upload-root]
  FileUpload
  (upload-file-path [_ session target-path action-options]
    (target upload-root (rsync-user session) target-path))
  (upload-file
    [_ session local-path target-path action-options]
    (rsync-upload-file
     local-path
     (target upload-root (rsync-user session) target-path)
     (target-ip session)
     (ssh-port (target-node session))
     (:username (admin-user session))
     (merge (-> (default-options session)
                (select-keys [:rsync-path]))
            {:chmod "go-w,go-r"}))))

(defn rsync-upload
  "Create an instance of the rsync upload strategy."
  [{:keys [upload-root] :as options}]
  (map->RsyncUpload (merge {:upload-root "/tmp"} options)))

(defmethod file-uploader :rsync
  [_ options]
  (rsync-upload options))
