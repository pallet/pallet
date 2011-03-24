(ns pallet.resource.format
    "Compatability namespace"
  (:require
   pallet.config-file.format
   [pallet.utils :as utils]))

(utils/forward-fns pallet.config-file.format sectioned-properties name-values)
