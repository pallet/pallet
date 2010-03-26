(ns pallet.crate.java
  (:use
   [pallet.resource.package :only [package package-manager]]
   [pallet.target :only [packager]]))

(defmulti java-package-name "lookup package name"
  (fn [mgr vendor component] mgr))

(def deb-package-names
     {:openjdk "openjdk-6-"
      :sun "sun-java6-"})

(defmethod java-package-name :aptitude [mgr vendor component]
  (str (deb-package-names vendor) (name component)))

(def yum-package-names
     {:openjdk "java"})

(defmethod java-package-name :yum [mgr vendor component]
  (yum-package-names vendor))

(def sun-rpm-path "http://cds.sun.com/is-bin/INTERSHOP.enfinity/WFS/CDS-CDS_Developer-Site/en_US/-/USD/VerifyItem-Start/jdk-6u18-linux-i586-rpm.bin?BundledLineItemUUID=wb5IBe.lDHsAAAEn5u8ZJpvu&OrderID=yJxIBe.lc7wAAAEn2.8ZJpvu&ProductID=6XdIBe.pudAAAAElYStRSbJV&FileName=/jdk-6u18-linux-i586-rpm.bin")

(defn java
  "Install java.  Options can be :sun, :openjdk, :jdk, :jre.
By default sun jdk will be installed."
  [& options]
  (let [vendors (or (seq (filter (set (keys package-names)) options))
                            [:sun])
        components (or (seq (filter #{:jdk :jre :bin} options))
                       [:bin :jdk])
        packager (packager)]
    (if (some #(= :sun %) vendors)
      (condp = packager
        :aptitude
        (do
          (package-manager :universe)
          (package-manager :multiverse)
          (package-manager :update))
        :yum
        (package :install "jpackage-utils")))
    (doseq [vendor vendors
            component components]
      (let [p (java-package-name packager vendor component)]
        (when (and (= packager :aptitude) (= vendor :sun))
          (package-manager
           :debconf
           (str p " shared/present-sun-dlj-v1-1 note")
           (str p " shared/accepted-sun-dlj-v1-1 boolean true")))
        (package p)))))
