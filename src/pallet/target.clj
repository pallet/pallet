(ns pallet.target
  "Provide information about the target image"
  (:require
   [org.jclouds.compute :as jclouds]))

(defn os-family
  "OS family"
  [target] (some (set (map (comp keyword str) (jclouds/os-families))) target))

(defn admin-group
  "Default administrator group"
  [target]
  (case (os-family target)
    :yum "wheel"
    "adm"))

(defn packager
  "Default package manager"
  [target]
  (cond
   (some #(#{:ubuntu :debian :jeos} %) target)
   :aptitude
   (some #(#{:centos :rhel} %) target)
   :yum
   (some #(#{:gentoo} %) target)
   :portage
   :else
   :aptitude))
