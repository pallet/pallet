(ns pallet.ssh.content-files
  "Location of intermediate content files."
  (:require
   [pallet.ssh.content-files.protocols :as impl]))

(defn content-path
  "Return a content path for intermediate content files."
  [cp session action-options path]
  (impl/content-path cp session action-options path))
