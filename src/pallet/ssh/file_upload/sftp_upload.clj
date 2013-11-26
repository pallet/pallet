(ns pallet.ssh.file-upload.sftp-upload
  "Implementation of file upload using SFTP.

  This assumes that chown/chgrp/chmod are all going to work."
  (:require
   [clojure.java.io :refer [input-stream]]
   [clojure.string :refer [blank?]]
   [clojure.tools.logging :refer [debugf]]
   [pallet.script.lib
    :refer [chgrp chmod chown dirname env exit file mkdir path-group
            path-owner user-home]]
   [pallet.ssh.execute :refer [with-connection]]
   [pallet.core.file-upload.protocols :refer [FileUpload]]
   [pallet.stevedore :refer [fragment]]
   [pallet.transport :as transport]
   [pallet.utils :refer [base64-md5]]))

(defn upload-dir
  "Return the upload directory for username. A :home at the start of the
  upload directory will be replaced by the user's home directory."
  [upload-root username]
  (if (.startsWith upload-root ":home")
    (let [path-rest (subs upload-root 5)]
      (if (blank? path-rest)
        (fragment (user-home ~username))
        (fragment (str (user-home ~username) ~path-rest))))
    (str upload-root "/" username)))

(defn target
  [upload-root username target-path]
  (str (upload-dir upload-root username)  "/" (base64-md5 target-path)))

(defn sftp-ensure-dir
  "Ensure directory exists"
  [connection target-path]
  (debugf "sftp-ensure-dir %s:%s"
          (:server (transport/endpoint connection)) target-path)
  (let [dir (fragment (dirname ~target-path))
        {:keys [exit] :as rv} (do
                                (debugf "Transfer: ensure dir %s" dir)
                                (transport/exec
                                 connection
                                 {:in (fragment
                                       (mkdir ~dir :path true)
                                       (chmod "0700" ~dir)
                                       (exit "$?"))}
                                 {}))]
    (when-not (zero? exit)
      (throw (ex-info
              (str "Failed to create target directory " dir ". " (:out rv))
              {:type :pallet/upload-fail
               :status rv})))))

(defn sftp-upload-file
  "Upload a file via SFTP"
  [connection local-path target-path]
  (debugf "sftp-upload-file %s:%s from %s"
          (:server (transport/endpoint connection))
          target-path local-path)
  (transport/send-stream
       connection
       (input-stream local-path)
       target-path
       {:mode 0600}))

(defrecord SftpUpload [upload-root]
  FileUpload
  (upload-file-path [_ target-path action-options]
    (target upload-root target-path))
  (upload-file
    [_ session local-path target-path action-options]
    (let [target (target target-path)]
      (with-connection session [connection]
        (sftp-ensure-dir target)
        (sftp-upload-file local-path target)))))

(defn sftp-upload
  "Create an instance of the SFTP upload strategy."
  [{:keys [upload-root] :as options}]
  (map->SftpUpload (merge
                    {:upload-root "/tmp"}
                    options)))
