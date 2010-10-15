(ns pallet.compute.jvm
  "Information from local jvm")

(def jvm-os-map
  {"Mac OS X" :os-x})

(defn os-name []
  (System/getProperty "os.name"))

(defn os-family []
  (jvm-os-map (os-name)))

(defn log4j?
 "Predicate to test for log4j on the classpath."
  []
  (try
    (import org.apache.log4j.Logger)
    true
    (catch java.lang.ClassNotFoundException _
      false)))
