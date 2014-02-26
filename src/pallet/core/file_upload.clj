(ns pallet.core.file-upload
  (:require
   [pallet.core.file-upload.protocols :as impl]))

(defn upload-file-path
  "Return the path to which upload-file would upload a file for
    target-path"
  [uploader target-path action-options]
  (impl/upload-file-path uploader target-path action-options))

(defn user-file-path
  [uploader target-path action-options]
  (impl/user-file-path uploader target-path action-options))

(defn upload-file
  "Upload a file to the target-path.

   action-options must contain a :user, specifying the user to install
   the file as."
  [uploader target local-path target-path action-options]
  (impl/upload-file uploader target local-path target-path action-options))

(defmulti file-uploader
  "Instantiate a file-upload provider based on keyword and option map."
  (fn [kw options] kw))
