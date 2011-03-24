(ns pallet.resource.remote-directory
  "Compatability namespace"
  (:require
   pallet.action.remote-directory
   [pallet.utils :as utils]))

(utils/forward-fns pallet.action.remote-directory remote-directory)
