(ns pallet.resource.filesystem
  "Compatability namespace"
  (:require
   pallet.action.filesystem
   [pallet.utils :as utils]))

(utils/forward-fns
 pallet.action.filesystem
 make-xfs-filesytem format-mount-option mount)
