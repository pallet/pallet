(ns pallet.crate.public-dns-if-no-nameserver
  (:use [pallet.resource.user :only [user]]
        [pallet.crate.resolv]
        [pallet.stevedore :only [script]]
        [pallet.utils :only [default-public-key-path]]))

(defonce google-dns ["8.8.8.8" "8.8.4.4"])
(defonce opendns-nameservers ["208.67.222.222" "208.67.220.220"])

(defn public-dns-if-no-nameserver
  "Install a public nameserver if nonone configured"
  [& nameservers]
  (let [nameservers (if (seq nameservers)
                      nameservers
                      (conj google-dns (first opendns-nameservers))) ]
    (script (if-not (nameservers)
              (do ~(resolv nil nameservers :rotate true))))))
