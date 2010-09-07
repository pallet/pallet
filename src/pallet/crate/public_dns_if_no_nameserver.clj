(ns pallet.crate.public-dns-if-no-nameserver
  (:require
   pallet.resource.hostinfo
   [pallet.resource.resource-when :as resource-when]
   [pallet.crate.resolv :as resolv]))

(defonce google-dns ["8.8.8.8" "8.8.4.4"])
(defonce opendns-nameservers ["208.67.222.222" "208.67.220.220"])

(defn public-dns-if-no-nameserver
  "Install a public nameserver if none configured"
  [request & nameservers]
  (let [nameservers (if (seq nameservers)
                      nameservers
                      (conj google-dns (first opendns-nameservers))) ]
    (-> request
        (resource-when/resource-when-not
         (nameservers)
         (resolv/resolv nil nameservers :rotate true)))))
