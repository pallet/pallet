(ns pallet.resource.lib
  "Compatibility namespace"
  (:require
   pallet.script.lib
   [pallet.utils :as utils]))

(utils/forward-to-script-lib
 file-changed
 set-flag
 flag?)
