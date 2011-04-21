(ns pallet.resource.filesystem-layout
  "Compatibility namespace"
  (:require
   pallet.script.lib
   [pallet.utils :as utils]))

(utils/forward-to-script-lib
 etc-default
 log-root
 pid-root
 config-root
 etc-hosts
 etc-init
 pkg-etc-default
 etc-default
 pkg-log-root
 pkg-pid-root
 pkg-config-root
 pkg-sbin)
