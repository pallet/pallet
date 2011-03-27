(ns pallet.target
  "Compatibility namespace"
  (:require
   [pallet.utils :as utils]))

(defn os-family
  "OS family"
  {:deprecated "0.5.0"}
  [target]
  (utils/deprecated
   "pallet.target/os-family is deprecated, please use pallet.session/os-family")
  (:os-family target))

(defn admin-group
  "Default administrator group"
  [target]
  (utils/deprecated
   "pallet.target/admin-group is deprecated, please use pallet.session/admin-group")
  (case (os-family target)
    :yum "wheel"
    "adm"))
