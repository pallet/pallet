(ns pallet.compute.jvm
  "Information from local jvm")

(def jvm-os-map
  {"Mac OS X" :os-x})

(defn os-name []
  (System/getProperty "os.name"))

(defn os-family []
  (jvm-os-map (os-name)))
