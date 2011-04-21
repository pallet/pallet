(ns pallet.resource.hostinfo
  "Compatibility namespace"
  (:require
   pallet.script.lib
   [pallet.common.deprecate :as deprecate]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]))

(utils/forward-to-script-lib
 os-version-name
 hostname
 dnsdomainname
 nameservers
 debian-version
 redhat-version
 ubuntu-version
 arch)

(defn architecture
  "Machine CPU architecture."
  {:deprecated "0.5.0"}
  []
  (deprecate/deprecated "pallet.resource.hostinfo/architecture is deprecated")
  (stevedore/script (~arch)))
