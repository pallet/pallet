(ns pallet.resource.network-service
  "Compatability namespace"
  (:require
   [pallet.common.deprecate :as deprecate]
   pallet.crate.network-service
   pallet.script.lib))

(deprecate/forward-fns
 pallet.crate.network-service
 wait-for-port-listen wait-for-http-status
 wait-for-port-response)
