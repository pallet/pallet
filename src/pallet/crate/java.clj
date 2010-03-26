(ns pallet.crate.java
  (:use [pallet.resource.package :only [package package-manager]]))

(def package-names
     {:openjdk "openjdk-6-"
      :sun "sun-java6-"})

(defn java
  "Install java.  Options can be :sun, :openjdk, :jdk, :jre.
By default sun jdk will be installed."
  [& options]
  (let [implementations (filter (set (keys package-names)) options)
        components (filter #{:jdk :jre :bin} options)]
    (package-manager :universe)
    (package-manager :multiverse)
    (package-manager :update)
    (doseq [implementation (or (seq implementations) [:sun])
            component (or (seq components) [:bin :jdk])]
      (let [p (str (package-names implementation) (name component))]
        (when (= implementation :sun)
          (package-manager
           :debconf
           (str p " shared/present-sun-dlj-v1-1 note")
           (str p " shared/accepted-sun-dlj-v1-1 boolean true")))
        (package p)))))
