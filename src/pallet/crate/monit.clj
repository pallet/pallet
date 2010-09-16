(ns pallet.crate.monit
  "Install monit"
  (:require
   [pallet.resource.package :as package]))

(defn package
  "Install monit from system package"
  [request]
  (->
   request
   (package/packages
    :yum ["monit"]
    :aptitude ["monit"])))

(defn monitor
  "Monitor something with monit"
  [request & {:as options}]
  )
