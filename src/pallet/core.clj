(ns pallet.core
  "Namespace for compatibility with pallet 0.7.x and earlier"
  (:require
   pallet.api)
  (:use
   [useful.ns :only [alias-ns defalias]]))

(alias-ns 'pallet.api)
