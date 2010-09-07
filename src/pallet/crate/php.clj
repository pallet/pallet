(ns pallet.crate.php
  (:require
   [pallet.resource.package :as package])
  (:use pallet.thread-expr))

(defn php
  "Install php"
  [request & extensions]
  (->
   request
   (package/packages :yum ["php5"] :aptitude ["php5"])
   (for-> [extension extensions]
     (package/package (format "php-%s" (name extension))))))
