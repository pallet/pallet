(ns pallet.core.file-upload.protocols
  "Protocols for file upload")

(defprotocol FileUpload
  (upload-file-path [_ target-path action-options]
    "Return the path to which upload-file would upload a file for
    target-path")
  (user-file-path [_ target-path action-options]
    "Return the path to which intermediate files should be written for the
    specified username.")
  (upload-file [_ target local-path target-path action-options]
    "Upload a file to the target-path.

    action-options must contain a :user, specifying the user to install
    the file as."))
