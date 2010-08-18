(ns pallet.resource.hostinfo-test
  (:use pallet.resource.hostinfo)
  (:use [pallet.stevedore :only [script]]
        clojure.test
        pallet.test-utils))

(deftest dnsdomainname-test
  (is (= "$(dnsdomainname)"
         (script (dnsdomainname)))))

(deftest dnsdomainname-test
  (is (= "$(hostname --fqdn)"
         (script (hostname :fqdn true)))))

(deftest nameservers-test
  (is (= "$(grep nameserver /etc/resolv.conf | cut -f2)"
         (script (nameservers)))))
