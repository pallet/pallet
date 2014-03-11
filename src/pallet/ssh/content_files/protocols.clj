(ns pallet.ssh.content-files.protocols
  "Protocols for node state updates")

(defprotocol ContentFiles
  (content-path [cp session action-options path]
    "Return a content path for intermediate content files."))
