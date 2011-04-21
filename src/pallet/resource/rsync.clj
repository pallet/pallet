(ns pallet.resource.rsync
  "Compatability namespace"
  (:require
   pallet.action.rsync
   [pallet.common.deprecate :as deprecate]))

(deprecate/forward-fns
 pallet.action.rsync
 rsync rsync-directory)
