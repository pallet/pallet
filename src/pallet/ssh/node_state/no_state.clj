(ns pallet.ssh.node-state.no-state
  "A node-state implementation that maintains no node state"
  (:require
   [pallet.ssh.node-state :refer [file-backup file-checksum]]
   [pallet.ssh.node-state.protocols :refer [FileBackup FileChecksum]]))

(defrecord NoBackup []
  FileBackup
  (new-file-content [_ session path options]))

(defrecord NoChecksum []
  FileChecksum
  (verify-checksum [_ session path])
  (record-checksum [_ session path]))

(defn no-backup []
  (NoBackup.))

(defn no-checksum []
  (NoChecksum.))

(defmethod file-backup :no-state
  [_ options]
  (no-backup))

(defmethod file-checksum :no-state
  [_ options]
  (no-checksum))
