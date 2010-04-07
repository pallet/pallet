(ns pallet.crate.tomcat
 "Installation of tomcat"
  (:use
   [pallet.stevedore :only [script]]
   [pallet.resource :only [defcomponent defresource]]
   [pallet.resource.file :only [heredoc]]
   [pallet.resource.remote-file :only [remote-file remote-file*]]
   [pallet.resource.exec-script :only [exec-script]]
   [pallet.resource.service :only [service]]
   [pallet.resource.package :only [package]]
   [clojure.contrib.prxml :only [prxml]])
  (:require
   [clojure.contrib.str-utils2 :as string]))

;; aptitude
;;

;; yum
;; jakarta-commons-collections-tomcat5.x86_64 : Jakarta Commons Collection dependency for Tomcat5
;; struts-webapps-tomcat5.x86_64 : Sample struts webapps for tomcat5
;; tomcat5.x86_64 : Apache Servlet/JSP Engine, RI for Servlet 2.4/JSP 2.0 API
;; tomcat5-admin-webapps.x86_64 : The administrative web applications for Jakarta Tomcat
;; tomcat5-common-lib.x86_64 : Libraries needed to run the Tomcat Web container (part)
;; tomcat5-jasper.x86_64 : Compiler JARs and associated scripts for tomcat5
;; tomcat5-jasper-javadoc.x86_64 : Javadoc generated documentation for tomcat5-jasper
;; tomcat5-jsp-2.0-api.x86_64 : Jakarta Tomcat Servlet and JSP implementation classes
;; tomcat5-jsp-2.0-api-javadoc.x86_64 : Javadoc generated documentation for tomcat5-jsp-2.0-api
;; tomcat5-server-lib.x86_64 : Libraries needed to run the Tomcat Web container (part)
;; tomcat5-servlet-2.4-api.x86_64 : Jakarta Tomcat Servlet implementation classes
;; tomcat5-servlet-2.4-api-javadoc.x86_64 : Javadoc generated documentation for tomcat5-servlet-2.4-api
;; tomcat5-webapps.x86_64 : Web applications for Jakarta Tomcat

(def tomcat-config-root "/etc/tomcat6/")
(def tomcat-doc-root "/var/lib/tomcat6/")

(defn tomcat
  "Install tomcat"
  []
  (package "tomcat6"))

(defn tomcat-deploy
  [warfile]
  (exec-script
   (script
    (cp ~warfile ~(str tomcat-doc-root "webapps/"))))
  ;; restart fails to regenerate security policy cache
  (service "tomcat6" :action :stop)
  (service "tomcat6" :action :start))

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
