(ns pallet.crate.resolv
  (:require [clojure.contrib.str-utils2 :as string])
  (:use
   [pallet.target :only [admin-group]]
   [pallet.stevedore :only [script]]
   [pallet.template]
   [pallet.resource :only [defresource]]
   [pallet.resource.user :only [user-home]]
   [pallet.resource.hostinfo :only [dnsdomainname]]
   [pallet.utils :only [as-string]]
   [clojure.contrib.logging]))


(defn- write-key-value [key value]
  (str (as-string key) " " (as-string value) \newline))

(defn- write-option [[key value]]
  (str (as-string key)
       (if (and value (not (instance? Boolean value)))
         (str ":" (as-string value)))))

(defn- write-options [options]
  (write-key-value "options" (string/join " " (map write-option options))))

(defn write [domainname nameservers searches sortlist options]
  (str (write-key-value "domain" (or domainname (str (script (dnsdomainname)))))
       (string/map-str (partial write-key-value "nameserver") nameservers)
       (when (first searches)
         (write-key-value "search" (string/join " " searches)))
       (when (first sortlist)
         (write-key-value "sortlist" (string/join " " sortlist)))
       (when (first options)
         (write-options options))))



(deftemplate resolv-templates
  [domainname nameservers searches sortlist options]
  {{:path "/etc/resolv.conf" :owner "root" :mode "0644"}
   (write domainname nameservers searches sortlist options)})


(def resolv-args (atom []))

(defn- ensure-vector [arg]
  (if (vector? arg)
    arg
    (if arg [arg] [])))

(defn- merge-resolve-spec [m1 m2]
  (let [[d1 n1 s1 r1 opt1] m1
        [d2 n2] m2
        opt2 (apply hash-map (drop 2 m2))
        r [(or d1 d2)
           (concat (ensure-vector n1) (ensure-vector n2))
           (concat s1 (ensure-vector (:search opt2)))
           (concat r1 (ensure-vector (:sortlist opt2)))
           (merge opt1 (dissoc (dissoc opt2 :search) :sortlist))]]
    (if-not (or (nil? d1) (nil? d2) (= d1 d2))
      (warn (str "Trying to set domain name to two distinct values")))
    r))


(defn- apply-resolv [args]
  (trace "apply-resolv")
  (apply-templates
   resolv-templates
   (reduce merge-resolve-spec [nil [] [] [] {}] args)))

(defresource resolv "Resolv configuration. Generates a resolv.conf file.
options are:

:search    search order
:sortlist  sortlist

or one of the resolv.conf options"
  resolv-args apply-resolv [domainname nameservers & options])



