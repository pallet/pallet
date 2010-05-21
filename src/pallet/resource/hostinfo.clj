(ns pallet.resource.hostinfo
  "Host information."
  (:require
   [pallet.script :as script]
   [pallet.stevedore :as stevedore])
  (:use
   [pallet.resource :only [defresource]]
   clojure.contrib.logging))

(script/defscript os-version-name [])
(stevedore/defimpl os-version-name :default []
  @(lsb_release -c -s))

(script/defscript hostname [& options])
(stevedore/defimpl hostname :default [& options]
  @("hostname"
    ~(if (first options)
       (stevedore/map-to-arg-string (apply hash-map options)))))

(script/defscript dnsdomainname [])
(stevedore/defimpl dnsdomainname :default []
  @("dnsdomainname"))

(script/defscript nameservers [])
(stevedore/defimpl nameservers :default []
  @("grep" nameserver "/etc/resolv.conf" | cut "-f2"))

(script/defscript debian-version [])
(stevedore/defimpl debian-version :default []
  (if (file-exists? "/etc/debian") (cat "/etc/debian")))

(script/defscript redhat-version [])
(stevedore/defimpl redhat-version :default []
  (if (file-exists? "/etc/redhat-release") (cat "/etc/redhat-release")))

(script/defscript ubuntu-version [])
(stevedore/defimpl ubuntu-version :default []
  (if (file-exists? "/usr/bin/lsb_release") @("/usr/bin/lsb_release" -c -s)))

(script/defscript arch [])
(stevedore/defimpl architecture :default []
  @(uname -p))

(defn architecture []
  "Machine CPU architecture."
  (stevedore/script (arch)))
