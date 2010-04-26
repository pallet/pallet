(ns pallet.resource.hostinfo
  "Host information."
  (:use pallet.script
        [pallet.resource :only [defresource]]
        pallet.stevedore
        clojure.contrib.logging))

(defscript os-version-name [])
(defimpl os-version-name :default []
  @(lsb_release -c -s))

(defscript hostname [& options])
(defimpl hostname :default [& options]
  @("hostname" ~(if (first options) (map-to-arg-string (apply hash-map options)))))

(defscript dnsdomainname [])
(defimpl dnsdomainname :default []
  @("dnsdomainname"))

(defscript nameservers [])
(defimpl nameservers :default []
  @("grep" nameserver "/etc/resolv.conf" | cut "-f2"))

(defscript debian-version [])
(defimpl debian-version :default []
  (if (file-exists? "/etc/debian") (cat "/etc/debian")))

(defscript redhat-version [])
(defimpl redhat-version :default []
  (if (file-exists? "/etc/redhat-release") (cat "/etc/redhat-release")))

(defscript ubuntu-version [])
(defimpl ubuntu-version :default []
  (if (file-exists? "/usr/bin/lsb_release") @("/usr/bin/lsb_release" -c -s)))

(defscript arch [])
(defimpl architecture :default []
  @(uname -p))

(defn architecture []
  "Machine CPU architecture."
  (script (arch)))
