(ns pallet.resource.format
    "Compatability namespace"
  (:require
   pallet.config-file.format
   [pallet.common.deprecate :as deprecate]))

(deprecate/forward-fns
 pallet.config-file.format sectioned-properties name-values)
