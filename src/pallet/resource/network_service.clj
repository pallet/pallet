(ns pallet.resource.network-service
  "Compatability namespace"
  (:require
   pallet.crate.network-service
   pallet.script.lib
   [pallet.utils :as utils]))

(utils/forward-fns
 pallet.crate.network-service
 wait-for-port-listen wait-for-http-status
 wait-for-port-response)
