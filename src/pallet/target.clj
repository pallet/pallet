(ns pallet.target
  "Provide information about the target image.

   This is not part of the pallet API."
  (:require
   [clojure.contrib.condition :as condition]))

(defn os-family
  "OS family"
  [target] (:os-family target))

(defn admin-group
  "Default administrator group"
  [target]
  (case (os-family target)
    :yum "wheel"
    "adm"))
