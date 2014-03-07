(ns pallet.core.protocols)

;;; # General
(defprotocol Closeable
  "Closeable protocol."
  (close [_] "Release acquired resources."))
