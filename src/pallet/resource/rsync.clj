(ns pallet.resource.rsync
  "Compatability namespace"
  (:require
   pallet.action.rsync
   [pallet.utils :as utils]))

(utils/forward-fns
 pallet.action.rsync
 rsync rsync-directory)
