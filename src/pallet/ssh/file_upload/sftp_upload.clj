(ns pallet.ssh.file-upload.sftp-upload
  "Implementation of file upload using SFTP.

  This assumes that chown/chgrp/chmod are all going to work."
  (:require
   [clojure.java.io :refer [input-stream]]
   [clojure.string :refer [blank?]]
   [clojure.tools.logging :refer [debugf]]
   [pallet.common.filesystem :as filesystem]
   [pallet.script.lib
    :refer [chgrp chmod chown dirname env exit file mkdir path-group
            path-owner user-home]]
   [pallet.ssh.execute :refer [with-connection]]
   [pallet.core.file-upload.protocols :refer [FileUpload]]
   [pallet.core.session :refer [admin-user]]
   [pallet.stevedore :refer [fragment]]
   [pallet.transport :as transport]
   [pallet.utils :refer [base64-md5]])
  (:import
   [java.security MessageDigest DigestInputStream]
   [org.apache.commons.codec.binary Base64]))

(defn md5-digest-input-stream
  "Return a tuple containing a MessageDigest and a DigestInputStream."
  [str]
  (let [md (MessageDigest/getInstance "MD5")]
    [md (DigestInputStream. str md)]))

(defn ^String md5 [path]
  (let [[^java.security.MessageDigest md s]
        (md5-digest-input-stream (input-stream path))]
    (with-open [s s]
      (slurp s))
    (Base64/encodeBase64URLSafeString (.digest md))))

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
  (let [dir (fragment @(dirname ~target-path))
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

(defn sftp-remote-md5
  "Return the md5 for a remote file."
  [connection md5-path]
  (try
    (filesystem/with-temp-file [md5-copy]
      (transport/receive connection md5-path (.getPath md5-copy))
      (slurp md5-copy))
    (catch Exception _ nil)))

(defn sftp-put-md5
  [connection path md5]
  (transport/send-text connection md5 path {:mode 0600}))

(defrecord SftpUpload [upload-root]
  FileUpload
  (upload-file-path [_ session target-path action-options]
    (target upload-root (:username (admin-user session)) target-path))
  (upload-file
    [_ session local-path target-path action-options]
    (let [target (target
                  upload-root (:username (admin-user session)) target-path)
          target-md5-path (str target ".md5")]
      (with-connection session [connection]
        (let [target-md5 (sftp-remote-md5 connection target-md5-path)
              local-md5 (md5 local-path)]
          (when-not (= target-md5 local-md5)
            (sftp-ensure-dir connection target)
            (sftp-upload-file connection local-path target)
            (sftp-put-md5 connection target-md5-path local-md5)))))))

(defn sftp-upload
  "Create an instance of the SFTP upload strategy."
  [{:keys [upload-root] :as options}]
  (map->SftpUpload (merge
                    {:upload-root "/tmp"}
                    options)))
