(ns pallet.crate.java
  "Crates for java installation and configuration"
  (:require
   [pallet.resource :as resource]
   [pallet.resource.package :as package]
   [pallet.resource.remote-file :as remote-file]
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target])
  (:use pallet.thread-expr))

(def vendor-keywords #{:openjdk :sun})

(def deb-package-names
     {:openjdk "openjdk-6-"
      :sun "sun-java6-"})

(def yum-package-names
     {:openjdk "java"})

(def pacman-package-names
  {:openjdk "openjdk6"})

(defmulti java-package-name "lookup package name"
  (fn [mgr vendor component] mgr))

(defmethod java-package-name :aptitude [mgr vendor component]
  (if (= vendor :sun)
    [(str (deb-package-names vendor) "bin")
     (str (deb-package-names vendor) (name component))]
    [(str (deb-package-names vendor) (name component))]))

(defmethod java-package-name :yum [mgr vendor component]
  [(yum-package-names vendor)])

(defmethod java-package-name :pacman [mgr vendor component]
  (if (= :sun vendor)
    [component]
    [(pacman-package-names vendor)]))

(def sun-rpm-path "http://cds.sun.com/is-bin/INTERSHOP.enfinity/WFS/CDS-CDS_Developer-Site/en_US/-/USD/VerifyItem-Start/jdk-6u18-linux-i586-rpm.bin?BundledLineItemUUID=wb5IBe.lDHsAAAEn5u8ZJpvu&OrderID=yJxIBe.lc7wAAAEn2.8ZJpvu&ProductID=6XdIBe.pudAAAAElYStRSbJV&FileName=/jdk-6u18-linux-i586-rpm.bin")

(defn java
  "Install java. Options can be :sun, :openjdk, :jdk, :jre.
   By default openjdk will be installed."
  [request & options]
  (let [vendors (or (seq (filter vendor-keywords options))
                    [:sun])
        components (or (seq (filter #{:jdk :jre} options))
                       [:jdk])
        packager (:target-packager request)]

    (let [vc (fn [request vendor component]
               (let [pkgs (java-package-name packager vendor component)]
                 (->
                  request
                  (for->
                   [p pkgs]
                   (when-> (and (= packager :aptitude) (= vendor :sun))
                           (package/package-manager
                            :debconf
                            (str
                             p " shared/present-sun-dlj-v1-1 note")
                            (str
                             p " shared/accepted-sun-dlj-v1-1 boolean true")))
                   (package/package p)))))]
      (->
       request
       (when-> (some #(= :sun %) vendors)
               (when-> (= packager :aptitude)
                       (package/package-manager :universe)
                       (package/package-manager :multiverse)
                       (package/package-manager :update))
               (when-> (= packager :yum)
                       (package/package "jpackage-utils")))
       (for-> [vendor vendors]
              (for-> [component components]
                     (vc vendor component)))))))

(script/defscript jre-lib-security [])
(stevedore/defimpl jre-lib-security :default []
  (str @(update-java-alternatives -l "|" cut "-d ' '" -f 3 "|" head -1)
       "/jre/lib/security/"))

(defn jce-policy-file
  "Installs a local JCE policy jar at the given path in the remote JAVA_HOME's
   lib/security directory, enabling the use of \"unlimited strength\" crypto
   implementations. Options are as for remote-file.

   e.g. (jce-policy-file
          \"local_policy.jar\" :local-file \"path/to/local_policy.jar\")

   Note this only intended to work for ubuntu/aptitude-managed systems and Sun
   JDKs right now."
  [request filename & {:as options}]
  (apply remote-file/remote-file request
    (stevedore/script (str (jre-lib-security) ~filename))
    (apply
     concat (merge {:owner "root" :group "root" :mode 644} options))))
