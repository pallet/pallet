(ns pallet.core.file-upload
  (:require
   [pallet.core.file-upload.protocols :as impl]))

(defn upload-file-path
  "Return the path to which upload-file would upload a file for
    target-path"
  [uploader session target-path action-options]
  (impl/upload-file-path uploader session target-path action-options))

(defn upload-file
  "Upload a file to the target-path, and return any script needed to
    be run on the node to get the file into place.

    file-options is a map of options as passed to remote-file, for file
    ownership, permissions, etc.

    action-options can contain a :sudo-user, specify the user to install
    the file as."
  [uploader session local-path target-path action-options]
  (impl/upload-file
   uploader session local-path target-path action-options))
