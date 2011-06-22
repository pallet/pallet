(ns pallet.resource.shell
  "Compatibility namespace"
  (:require
   pallet.script.lib
   [pallet.utils :as utils]))

(utils/forward-to-script-lib
 exit
 xargs
 which)
