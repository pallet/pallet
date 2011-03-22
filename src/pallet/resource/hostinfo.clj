(ns pallet.resource.hostinfo
  "Host information."
  (:require
   pallet.resource.script
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore.script :as script-impl]))

(script/defscript os-version-name [])
(script-impl/defimpl os-version-name [#{:ubuntu :debian}] []
  @(lsb_release -c -s))

(script-impl/defimpl os-version-name :default []
  "")

(script/defscript hostname [& options])
(script-impl/defimpl hostname :default [& options]
  @("hostname"
    ~(if (first options)
       (stevedore/map-to-arg-string (apply hash-map options)))))

(script/defscript dnsdomainname [])
(script-impl/defimpl dnsdomainname :default []
  @("dnsdomainname"))

(script/defscript nameservers [])
(script-impl/defimpl nameservers :default []
  @("grep" nameserver "/etc/resolv.conf" | cut "-f2"))

(script/defscript debian-version [])
(script-impl/defimpl debian-version :default []
  (if (file-exists? "/etc/debian") (cat "/etc/debian")))

(script/defscript redhat-version [])
(script-impl/defimpl redhat-version :default []
  (if (file-exists? "/etc/redhat-release") (cat "/etc/redhat-release")))

(script/defscript ubuntu-version [])
(script-impl/defimpl ubuntu-version :default []
  (if (file-exists? "/usr/bin/lsb_release") @("/usr/bin/lsb_release" -c -s)))

(script/defscript arch [])
(script-impl/defimpl arch :default []
  @(uname -p))

(defn architecture []
  "Machine CPU architecture."
  (stevedore/script (arch)))
