(ns pallet.core.protocols
  (:require
   [clojure.core.async.impl.protocols :refer [Channel]]
   [clojure.core.typed :refer [defprotocol>]]
   [pallet.core.type-annotations]))

;;; # General
(defprotocol> Closeable
  "Closeable protocol."
  (close [_] "Release acquired resources."))
