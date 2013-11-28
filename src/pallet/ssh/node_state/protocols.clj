(ns pallet.ssh.node-state.protocols
  "Protocols for node state updates")

(defprotocol FileBackup
  (new-file-content [_ action-options path options]
    "Notify that path has been modified.
    Options include, :versioning, :no-versioning, :max-versions"))

(defprotocol FileChecksum
  (verify-checksum [_ action-options path]
    "Verify the expected MD5 of the file at path.")
  (record-checksum [_ action-options path]
    "Save the MD5 for the file at path."))
