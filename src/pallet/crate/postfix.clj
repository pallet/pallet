(ns pallet.crate.postfix
  "Install postfix.
   To reconfigure: sudo dpkg-reconfigure postfix"
  (:use
   [pallet.resource.package :as package]))

(def mailer-types
     {:internet-site "Internet Site"})

(defn postfix
  [mailname mailer-type]
  (package/package-manager
   :debconf
   (str "postfix postfix/mailname string " mailname)
   (str "postfix postfix/main_mailer_type select "
        (mailer-type mailer-types (name mailer-type))))
  (package/packages
   :yum ["postfix"]
   :aptitude ["postfix"]))
