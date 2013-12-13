(ns pallet.core.file-upload.protocols
  "Protocols for file upload")

(defprotocol FileUpload
  (upload-file-path [_ session target-path action-options]
    "Return the path to which upload-file would upload a file for
    target-path")
  (upload-file [_ session local-path target-path action-options]
    "Upload a file to the target-path, and return any script needed to
    be run on the node to get the file into place.

    file-options is a map of options as passed to remote-file, for file
    ownership, permissions, etc.

    action-options can contain a :sudo-user, specify the user to install
    the file as."))
