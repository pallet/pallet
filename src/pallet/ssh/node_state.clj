(ns pallet.ssh.node-state
  "Node state tracking"
  (:require
   [pallet.ssh.node-state.protocols :as impl]))

(defn new-file-content
  "Notify that path has been modified.
    Options include, :versioning, :no-versioning, :max-versions"
  [ns session path options]
  (impl/new-file-content ns session path options))

(defn verify-checksum
  "Verify the expected MD5 of the file at path."
  [ns session path]
  (impl/verify-checksum ns session path))

(defn record-checksum
  "Save the MD5 for the file at path."
  [ns session path]
  (impl/record-checksum ns session path))

(defmulti file-backup
  "Instantiate a file-backup provider based on keyword and option map."
  (fn [kw options] kw))

(defmulti file-checksum
  "Instantiate a file-checksum provider based on keyword and option map."
  (fn [kw options] kw))
