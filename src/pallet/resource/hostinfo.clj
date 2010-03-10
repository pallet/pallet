(ns #^{ :doc "Host information."}
  pallet.resource.hostinfo
  (:require [clojure.contrib.str-utils2 :as string])
  (:use pallet.script
        [pallet.resource :only [defresource]]
        pallet.stevedore
        [clojure.contrib.def :only [defvar-]]
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

