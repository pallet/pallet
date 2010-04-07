(ns pallet.crate.hudson
 "Installation of hudson"
  (:use
   [pallet.crate.tomcat :only [tomcat-deploy tomcat-policy tomcat-application-conf]]
   [pallet.resource.remote-file :only [remote-file]]
   [pallet.resource.user :only [user]]))

(def hudson-data-path "/var/lib/hudson")

(defn hudson
  "Install hudson"
  []
  (let [file "/tmp/hudson.war"]
    (user "hudson" :shell :false :home hudson-data-path)
    (remote-file file
     :source "http://hudson-ci.org/latest/hudson.war"
     :md5  "680e1525fca0562cfd19552b8d8174e2")
    (tomcat-policy
     99 "hudson"
     {"file:${catalina.base}/webapps/hudson/-"
            ["permission java.security.AllPermission"]})
    (tomcat-application-conf
     "hudson"
     (format "<?xml version=\"1.0\" encoding=\"utf-8\"?>
<Context
docBase=\"/tmp/hudson.war\"
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
