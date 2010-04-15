(ns pallet.crate.tomcat
 "Installation of tomcat"
  (:use
   [pallet.stevedore :only [script]]
   [pallet.resource :only [defcomponent defresource]]
   [pallet.resource.file :only [heredoc file]]
   [pallet.resource.remote-file :only [remote-file remote-file*]]
   [pallet.resource.exec-script :only [exec-script]]
   [pallet.resource.service :only [service]]
   [pallet.resource.package :only [package]]
   [clojure.contrib.prxml :only [prxml]])
  (:require
   [clojure.contrib.str-utils2 :as string]))

(def tomcat-config-root "/etc/tomcat6/")
(def tomcat-doc-root "/var/lib/tomcat6/")

(defn tomcat
  "Install tomcat"
  [] (package "tomcat6"))

(defmacro with-restart
  [& body]
  ;; restart fails to regenerate security policy cache
  `(do
     (service "tomcat6" :action :stop)
     ~@body
     (service "tomcat6" :action :start)))

(defn tomcat-deploy
  "Copies the specified remote .war file to the tomcat server under webapps/${app-name}.war.
   An app-name of \"ROOT\" or nil will deploy the source war file as the / webapp. Options:

   :clear-existing true -- will remove the existing exploded ${app-name} directory"
  [warfile app-name & opts]
  (let [opts (apply hash-map opts)
        exploded-app-dir (str tomcat-doc-root "webapps/" (or app-name "ROOT"))
        deployed-warfile (str exploded-app-dir ".war")]
    (exec-script
      (script
        (cp ~warfile ~deployed-warfile)))
    (file deployed-warfile :owner "tomcat6" :group "tomcat6" :mode 600)
    (when (:clear-existing opts)
      (exec-script
        (script (rm ~exploded-app-dir ~{:r true :f true}))))))

(defn deploy-local-file
  [warfile app-name & opts]
  (let [temp-remote-file (str "pallet-tomcat-deploy-" (java.util.UUID/randomUUID))]
    (remote-file temp-remote-file :local-file warfile)
    (apply tomcat-deploy temp-remote-file app-name opts)
    (exec-script (script (rm ~temp-remote-file)))))

(defn output-grants [[code-base permissions]]
  (str
   "grant codeBase \"" code-base "\" {" \newline
   (string/join ";\n" permissions) ";\n"
   "};"))

(defn tomcat-policy*
  [number name grants]
  (remote-file*
   (str tomcat-config-root "policy.d/" number name ".policy")
   :content (string/join \newline (map output-grants grants))
   :literal true))

(defcomponent tomcat-policy
  "Configure tomcat policies.
number - determines sequence i which policies are applied
name - a name for the policy
grants - a map from codebase to sequence of permissions"
  tomcat-policy* [number name grants])

(defn tomcat-application-conf*
  [name content]
  (remote-file*
   (str tomcat-config-root "Catalina/localhost/" name ".xml")
   :content content
   :literal true))

(defcomponent tomcat-application-conf
  "Configure tomcat applications.
name - a name for the policy
content - an xml application context"
  tomcat-application-conf* [name content])


(defn tomcat-users*
  [roles users]
  (with-out-str
    (prxml
     [:decl {:version "1.1"}]
     [:tomcat-users
      (map #(vector :role {:rolename %}) roles)
      (map #(vector :user {:username (first %)
                           :password ((second %) :password)
                           :roles (string/join "," ((second %) :roles))})
           users)])))

(def tomcat-user-args (atom []))

(defn merge-tomcat-users [args]
  (loop [args args
         users {}
         roles []]
    (if (seq args)
      (if (= :role (first args))
        (recur (nnext args) users (conj roles (fnext args)))
        (recur (nnext args) (merge users {(first args) (fnext args)}) roles))
      [roles users])))

(defn apply-tomcat-user [args]
  (let [[roles users] (merge-tomcat-users (apply concat args))]
    (tomcat-users* roles users)))

(defresource tomcat-user
  "Configure tomcat users.
   options are:

   :role rolename
   username {:password \"pw\" :roles [\"role1\" \"role 2\"]}"
  tomcat-user-args apply-tomcat-user [& options])
