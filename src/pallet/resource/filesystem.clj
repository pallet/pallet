(ns pallet.resource.filesystem
  "Compatability namespace"
  (:require
   pallet.action.filesystem
   [pallet.common.deprecate :as deprecate]))

(deprecate/forward-fns
 pallet.action.filesystem
 make-xfs-filesytem format-mount-option mount)
