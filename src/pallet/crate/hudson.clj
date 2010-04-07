(ns pallet.crate.hudson
 "Installation of hudson"
  (:use
   [pallet.crate.tomcat :only [tomcat-deploy tomcat-policy tomcat-application-conf]]
   [pallet.resource :only [defcomponent]]
   [pallet.resource.service :only [service]]
   [pallet.resource.directory :only [directory]]
   [pallet.resource.remote-file :only [remote-file remote-file*]]
   [pallet.resource.user :only [user]]))

(def hudson-data-path "/var/hudson")

(defn hudson
  "Install hudson"
  []
  (let [file (str hudson-data-path "/hudson.war")]
    (directory hudson-data-path :owner "root" :group "tomcat6" :mode "775")
    (remote-file file
     :source "http://hudson-ci.org/latest/hudson.war"
     :md5  "680e1525fca0562cfd19552b8d8174e2")
    (tomcat-policy
     99 "hudson"
     {(str "file:${catalina.base}/webapps/hudson/-")
      ["permission java.security.AllPermission"]
      (str "file:" hudson-data-path "/-")
      ["permission java.security.AllPermission"]})
    (tomcat-application-conf
      "hudson"
      (format "<?xml version=\"1.0\" encoding=\"utf-8\"?>
 <Context
 privileged=\"true\"
 path=\"/hudson\"
 allowLinking=\"true\"
 swallowOutput=\"true\"
 >
 <Environment
 name=\"HUDSON_HOME\"
 value=\"%s\"
 type=\"java.lang.String\"
 override=\"false\"/>
 </Context>"
              hudson-data-path))
    (tomcat-deploy file)))

(def hudson-plugins
     {:git "https://hudson.dev.java.net/files/documents/2402/135478/git.hpi"})

(defn hudson-plugin*
  [plugin & options]
  (let [opts (if (seq options) (apply hash-map options) {})]
    (remote-file*
     (str hudson-data-path "/plugins/" (name plugin) ".hpi")
     :source (or (opts :source) (hudson-plugins plugin)))))

(defcomponent hudson-plugin
  "Install a hudson plugin.  The plugin should be a keyword.
  :source can be used to specify a string containing the download url"
  hudson-plugin* [plugin & options])

;; (defn hudson-config*
;;   [& options])

;; (def hudson-config-args (atom []))

;; (defn apply-hudson-config [args]
;;   (apply-templates
;;    sudoer-templates
;;    (reduce merge [(array-map) (array-map) (default-specs)] args)))

;; (defresource hudson-config
;;   "Configure hudson"
;;   hudson-config-args apply-hudson-config [& options])
