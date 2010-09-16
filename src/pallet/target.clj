(ns pallet.target
  "Provide information about the target image"
  (:require
   [org.jclouds.compute :as jclouds]
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

(defn packager
  "Default package manager"
  [target]
  (let [os-family (:os-family target)]
    (cond
     (#{:ubuntu :debian :jeos} os-family) :aptitude
     (#{:centos :rhel} os-family) :yum
     (#{:gentoo} os-family) :portage
     :else (condition/raise
            :type :unknown-packager
            :message (format
                      "Unknown packager for %s : :image %s"
                      os-family target)))))
