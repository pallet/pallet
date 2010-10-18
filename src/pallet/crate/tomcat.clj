(ns pallet.crate.tomcat
  "Installation of tomcat"
  (:refer-clojure :exclude [alias])
  (:use
   [pallet.stevedore :only [script]]
   [pallet.resource.file :only [heredoc file rm]]
   [pallet.template :only [find-template]]
   [pallet.enlive :only [xml-template xml-emit transform-if deffragment elt]]
   [clojure.contrib.prxml :only [prxml]]
   pallet.thread-expr)
  (:require
   [pallet.resource :as resource]
   [pallet.core :as core]
   [pallet.parameter :as parameter]
   [pallet.resource.package :as package]
   [pallet.resource.file :as file]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.directory :as directory]
   [pallet.resource.service :as service]
   [pallet.resource.exec-script :as exec-script]
   [pallet.request-map :as request-map]
   [pallet.target :as target]
   [net.cgrand.enlive-html :as enlive]
   [clojure.contrib.string :as string]))

(def tomcat-config-root "/etc/")
(def tomcat-base "/var/lib/")

(def package-name
  {:aptitude "tomcat6"
   :pacman "tomcat"
   :yum "tomcat5"
   :amzn-linux "tomcat6"})

(def tomcat-user-group-name
  {:aptitude "tomcat6"
   :pacman "tomcat"
   :yum "tomcat"})

(defn tomcat
  "Install tomcat"
  [request & {:keys [user group] :as options}]
  (let [package (or
                 (package-name (request-map/os-family request))
                 (package-name (:target-packager request)))
        tomcat-user (tomcat-user-group-name (:target-packager request))]
    (-> request
        (when-> (= :install (:action options :install))
                (parameter/assoc-for-target
                 [:tomcat :base] (str tomcat-base package "/")
                 [:tomcat :config-path] (str tomcat-config-root package "/")
                 [:tomcat :owner] (or user tomcat-user)
                 [:tomcat :group] (or group tomcat-user)
                 [:tomcat :service] package))
        (apply->
         package/package package
         (apply concat options))
        (when-> (:purge options)
                (directory/directory
                 tomcat-base :action :delete :recursive true :force true)))))

(defn init-service
  [request & args]
  (->
   request
   (apply->
    service/service
    (parameter/get-for-target request [:tomcat :service])
    args)))

(defn undeploy
  "Removes the named webapp directories, and any war files with the same base
   names."
  [request & app-names]
  (let [tomcat-base (parameter/get-for-target request [:tomcat :base])]
    (-> request
        (for-> [app-name app-names
                :let [app-name (or app-name "ROOT")
                      app-name (if (string? app-name) app-name (name app-name))
                      exploded-app-dir (str tomcat-base "webapps/" app-name)]]
               (directory/directory exploded-app-dir :action :delete)
               (file/file (str exploded-app-dir ".war") :action :delete)))))

(defn undeploy-all
  "Removes all deployed war file and exploded webapp directories."
  [request]
  (let [tomcat-base (parameter/get-for-target request [:tomcat :base])]
    (exec-script/exec-script
     request
     (rm ~(str tomcat-base "webapps/*") ~{:r true :f true}))))

(defn deploy
  "Copies a .war file to the tomcat server under webapps/${app-name}.war.  An
   app-name of \"ROOT\" or nil will deploy the source war file as the / webapp.

   Accepts options as for remote-file in order to specify the source.

   Other Options:
     :clear-existing true -- removes the existing exploded ${app-name} directory"
  [request app-name & {:as opts}]
  (let [tomcat-base (parameter/get-for-target request [:tomcat :base])
        tomcat-user (parameter/get-for-target request [:tomcat :owner])
        tomcat-group (parameter/get-for-target request [:tomcat :group])
        exploded-app-dir (str tomcat-base "webapps/" (or app-name "ROOT"))
        deployed-warfile (str exploded-app-dir ".war")
        options (merge
                 {:owner tomcat-user :group tomcat-group :mode 600}
                 (select-keys opts remote-file/all-options))]
    (->
     request
     (apply->
      remote-file/remote-file
      deployed-warfile
      (apply concat options))
     ;; (when-not-> (:clear-existing opts)
     ;;  ;; if we're not removing an existing, try at least to make sure
     ;;  ;; that tomcat has the permissions to explode the war
     ;;  (apply->
     ;;   directory/directory
     ;;   exploded-app-dir
     ;;   (apply concat
     ;;          (merge {:owner tomcat-user :group tomcat-group :recursive true}
     ;;                 (select-keys options [:owner :group :recursive])))))
     (when-> (:clear-existing opts)
             (directory/directory exploded-app-dir :action :delete)))))

(defn output-grants [[code-base permissions]]
  (let [code-base (when code-base
                    (format "codeBase \"%s\"" code-base))]
  (format
    "grant %s {\n  %s;\n};"
    (or code-base "")
    (string/join ";\n  " permissions))))

(defn policy
  "Configure tomcat policies.
     number - determines sequence i which policies are applied
     name - a name for the policy
     grants - a map from codebase to sequence of permissions"
  [request number name grants
   & {:keys [action] :or {action :create} :as options}]
  (let [tomcat-config-root (parameter/get-for-target
                            request [:tomcat :config-path])
        policy-file (str tomcat-config-root "policy.d/" number name ".policy")]
    (case action
      :create (->
               request
               (directory/directory
                (str tomcat-config-root "policy.d"))
               (remote-file/remote-file
                policy-file
                :content (string/join \newline (map output-grants grants))
                :literal true))
      :remove (file/file request policy-file :action :delete))))

(defn application-conf
  "Configure tomcat applications.
   name - a name for the policy
   content - an xml application context"
  [request name content & {:keys [action] :or {action :create} :as options}]
  (let [tomcat-config-root (parameter/get-for-target
                            request [:tomcat :config-path])
        app-file (str tomcat-config-root "Catalina/localhost/" name ".xml")]
    (case action
      :create (remote-file/remote-file
               request app-file :content content :literal true)
      :remove (file/file request app-file :action :delete))))

(defn users*
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

(defn merge-tomcat-users [args]
  (loop [args args
         users {}
         roles []]
    (if (seq args)
      (if (= :role (first args))
        (recur (nnext args) users (conj roles (fnext args)))
        (recur (nnext args) (merge users {(first args) (fnext args)}) roles))
      [roles users])))

(resource/defcollect user
  "Configure tomcat users. Options are:
     :role rolename
     username {:password \"pw\" :roles [\"role1\" \"role 2\"]}"
  {:use-arglist [request & {:keys [role] :as options}]}
  (apply-tomcat-user
   [request args]
   (let [[roles users] (merge-tomcat-users (apply concat args))]
     (users* roles users))))

(def listener-classnames
     {:apr-lifecycle
      "org.apache.catalina.core.AprLifecycleListener"
      :jasper
      "org.apache.catalina.core.JasperListener"
      ::server-lifecycle
      "org.apache.catalina.mbeans.ServerLifecycleListener"
      ::global-resources-lifecycle
      "org.apache.catalina.mbeans.GlobalResourcesLifecycleListener"
     :jmx-remote-lifecycle
      "org.apache.catalina.mbeans.JmxRemoteLifecycleListener"
     :jre-memory-leak-prevention
      "org.apache.catalina.mbeans.JmxRemoteLifecycleListener"})

(def connector-classnames
     {})

(def resource-classnames
     {:sql-data-source "javax.sql.DataSource"})

(def valve-classnames
     {:access-log "org.apache.catalina.valves.AccessLogValve"
      :remote-addr "org.apache.catalina.valves.RemoteAddrValve"
      :remote-host "org.apache.catalina.valves.RemoteHostValve"
      :request-dumper "org.apache.catalina.valves.RequestDumperValve"
      :single-sign-on "org.apache.catalina.authenticator.SingleSignOn"
      :basic-authenticator "org.apache.catalina.authenticator.BasicAuthenticator"
      :digest-authenticator "org.apache.catalina.authenticator.DigestAuthenticator"
      :form-authenticator "org.apache.catalina.authenticator.FormAuthenticator"
      :ssl-authenticator "org.apache.catalina.authenticator.SSLAuthenticator"
      :webdav-fix "org.apache.catalina.valves.WebdavFixValve"
      :remote-ip "org.apache.catalina.valves.RemoteIpValve"})

(def realm-classnames
     {:jdbc "org.apache.catalina.realm.JDBCRealm"
      :data-source "org.apache.catalina.realm.DataSourceRealm"
      :jndi "org.apache.catalina.realm.JNDIRealm"
      :user-database "org.apache.catalina.realm.UserDatabaseRealm"
      :memory "org.apache.catalina.realm.MemoryRealm"
      :jaas "org.apache.catalina.realm.JAASRealm"
      :combined "org.apache.catalina.realm.CombinedRealm"
      :lock-out "org.apache.catalina.realm.LockOutRealm"})

(def *server-file* "server.xml")
(def *context-file* "context.xml")
(def *web-file* "web.xml")

(defn path-for
  "Get the actual filename corresponding to a template."
  [base] (str "crate/tomcat/" base))


(defn flatten-map
  "Flatten a map, removing all namespaced keywords and specified keys"
  [m & dissoc-keys]
  (apply concat (remove (fn [[k v]]
                          (and (keyword? k) (namespace k)))
                  (apply dissoc m dissoc-keys))))

(deffragment server-resources-transform
  [global-resources]
  [:Environment] (transform-if (global-resources ::environment) nil)
  [:Resource] (transform-if (global-resources ::resource) nil)
  [:Transaction] (transform-if (global-resources ::transaction) nil)
  [:GlobalNamingResources]
  (enlive/do-> ; ensure we have elements to configure
   (transform-if (global-resources ::environment)
                 (enlive/prepend (elt :Environment)))
   (transform-if (global-resources ::resource)
                 (enlive/prepend (elt ::resource)))
   (transform-if (global-resources ::transaction)
                 (enlive/prepend (elt :Transaction))))
  [:Environment]
  (transform-if (global-resources ::environment)
   (enlive/clone-for [environment (global-resources ::environment)]
                     (apply enlive/set-attr (flatten-map environment))))
  [:Resource]
  (transform-if (global-resources ::resource)
   (enlive/clone-for [resource (global-resources ::resource)]
                     (apply enlive/set-attr (flatten-map resource))))
  [:Transaction]
  (transform-if (global-resources ::transaction)
   (enlive/clone-for [transaction (global-resources ::transaction)]
                     (apply enlive/set-attr (flatten-map transaction)))))

(deffragment engine-transform
  [engine]
  [:Host] (transform-if (engine ::host) nil)
  [:Valve] (transform-if (engine ::valve) nil)
  [:Realm] (transform-if (engine ::realm) nil)
  [:Engine]
  (enlive/do-> ; ensure we have elements to configure
   (transform-if (engine ::host)
                 (enlive/prepend (elt :Host)))
   (transform-if (engine ::valve)
                 (enlive/prepend (elt :Valve)))
   (transform-if (engine ::realm)
                 (enlive/prepend (elt :Realm))))
  [:Host]
  (transform-if (engine ::host)
                (enlive/clone-for
                 [host (engine ::host)]
                 (enlive/do->
                  (apply enlive/set-attr (flatten-map host))
                  (engine-transform (engine ::host)))))
  [:Valve]
  (transform-if (engine ::valve)
                (enlive/clone-for
                 [valve (engine ::valve)]
                 (apply enlive/set-attr (flatten-map valve))))
  [:Realm]
  (transform-if (engine ::realm)
                (enlive/set-attr (flatten-map (engine ::realm)))))

(deffragment service-transform
  [service]
  [:Connector]
  (enlive/clone-for [connector (service ::connector)]
                    (apply enlive/set-attr (flatten-map connector)))
  [:Engine]
  (transform-if (service ::engine)
                (enlive/do->
                 (apply enlive/set-attr (flatten-map (service ::engine)))
                 (engine-transform (service ::engine)))))

(defn tomcat-server-xml
  "Generate server.xml content"
  [node-type server]
  {:pre [node-type]}
  (xml-emit
   (xml-template
    (path-for *server-file*) node-type [server]
    [:Listener]
    (transform-if (server ::listener) nil)
    [:GlobalNamingResources]
    (transform-if (server ::global-resources) nil)
    [:Service] (transform-if (server ::service)
                 (enlive/clone-for [service (server ::service)]
                   (service-transform service)))
    [:Server]
    (enlive/do->
     (transform-if (seq (apply concat (select-keys server [:port :shutdown])))
                   (apply enlive/set-attr
                          (apply concat (select-keys server [:port :shutdown]))))
     (transform-if (server ::listener)
                   (enlive/prepend (elt :Listener)))
     (transform-if (server ::global-resources)
                   (enlive/prepend
                    (elt :GlobalNamingResources))))
    [:Listener]
    (transform-if (server ::listener)
      (enlive/clone-for
       [listener (server ::listener)]
       (apply enlive/set-attr (flatten-map listener))))
    [:GlobalNamingResources]
    (transform-if (server ::global-resources)
      (server-resources-transform (server ::global-resources))))
   server))

(defn classname-for
  "Lookup value in the given map if it is a keyword, else return the value."
  [value classname-map]
  (if (keyword? value)
    (classname-map value)
    value))

(defn extract-member-keys [options]
  (loop [options (seq options)
         output []
         members #{}
         collections #{}]
    (if options
      (condp = (first options)
        :members (recur (nnext options) output (set (fnext options)) collections)
        :collections (recur (nnext options) output members (set (fnext collections)))
        (recur (next options) (conj output (first options)) members collections))
      [members collections output])))


(defn extract-nested-maps
  ""
  [[members collections options]]
  (let [pallet-type (fn [object]
                      (and (map? object) (object ::pallet-type)))
        add-member (fn [result object]
                     (if-let [pt (pallet-type object)]
                       (assoc result pt
                              (if (members pt)
                                object
                                (conj (or (result pt) []) object)))
                       result))
        members (reduce add-member {} options)
        options (filter (complement pallet-type) options)]
    (merge members (into {} (map vec (partition 2 options))))))


(defn extract-options [& options]
  (extract-nested-maps (extract-member-keys options)))

(defmacro pallet-type
  "Create a pallet type-map"
  [type-tag & options]
  `(assoc (apply extract-options ~@options) ::pallet-type ~type-tag))

(defn listener
  "Define a tomcat listener. listener-type is a classname or a key from
   listener-classnames. Options are listener-type specific, and match
   the attributes in the tomcat docs.
   For example, to configure the APR SSL support:
     (listener :apr-lifecycle :SSLEngine \"on\" :SSLRandomSeed \"builtin\")"
  [listener-type & options]
  (pallet-type ::listener
               :className (classname-for listener-type listener-classnames)
               options))

(defn global-resources
  "Define tomcat resources.
   Options include:
     resources, transactions, environments"
  [& options]
  (pallet-type ::global-resources
               :collections [::resource ::transaction ::environment]
               options))

(defn environment
  "Define tomcat environment variable."
  ([name value type]
     (pallet-type ::environment
                  [:name name :value value :type (.getName type)]))
  ([name value type override]
     (pallet-type ::environment
                  [:name name :value value :type (.getName type)
                   :override override])))

(defn resource
  "Define tomcat JNDI resource.
   resource-type is a classname, or on of :sql-datasource.
   Options include:"
  [name resource-type & options]
  (pallet-type ::resource
               :name name
               :type (classname-for resource-type resource-classnames)
               options))

(defn transaction
  "Define tomcat transaction factory."
  [factory-classname]
  (pallet-type ::transaction [:factory factory-classname]))

(defn service
  "Define a tomcat service"
  [& options]
  (pallet-type ::service :members [::engine] :collections [::connector] options))

(defn connector
  "Define a tomcat connector."
  [& options]
  (pallet-type ::connector options))

(defn ssl-jsse-connector
  "Define a SSL connector using JSEE.  This connector can be specified for a
   service.

   This connector has defaults equivalant to:
     (tomcat/connector :port 8443 :protocol \"HTTP/1.1\" :SSLEnabled \"true\"
       :maxThreads 150 :scheme \"https\" :secure \"true\"
       :clientAuth \"false\" :sslProtocol \"TLS\"
       :keystoreFile \"${user.home}/.keystore\" :keystorePass \"changeit\")"
  [& options]
  (pallet-type
   ::connector
   (concat [:port 8443 :protocol "HTTP/1.1" :SSLEnabled "true"
            :maxThreads 150 :scheme "https" :secure "true"
            :clientAuth "false" :sslProtocol "TLS"
            :keystoreFile "${user.home}/.keystore" :keystorePass "changeit"]
           options)))

(defn ssl-apr-connector
  "Define a SSL connector using APR.  This connector can be specified for a
   service.  You can use the :SSLEngine and :SSLRandomSeed options on the
   server's APR lifecycle listener to configure which engine is used.

   This connector has defaults equivalant to:
     (tomcat/connector :port 8443 :protocol \"HTTP/1.1\" :SSLEnabled \"true\"
       :maxThreads 150 :scheme \"https\" :secure \"true\"
       :clientAuth \"optional\" :sslProtocol \"TLSv1\"
       :SSLCertificateFile \"/usr/local/ssl/server.crt\"
       :SSLCertificateKeyFile=\"/usr/local/ssl/server.pem\")"
  [& options]
  (pallet-type
   ::connector
   (concat [:port 8443 :protocol "HTTP/1.1" :SSLEnabled "true"
            :maxThreads 150 :scheme "https" :secure "true"
            :clientAuth "optional" :sslProtocol "TLSv1"
            :SSLCertificateFile "/usr/local/ssl/server.crt"
            :SSLCertificateKeyFile="/usr/local/ssl/server.pem"]
           options)))

(defn engine
  "Define a tomcat engine. Options are:
     valves, realm, hosts"
  [name default-host & options]
  (pallet-type
   ::engine :members [::realm] :collections [::valve ::host]
   :name name :defaultHost default-host options))

;; TODO : Create specialised constructors for each realm
(defn realm
  "Define a tomcat realm."
  [realm-type & options]
  (pallet-type
   ::realm :className (classname-for realm-type realm-classnames) options))

(defn valve
  "Define a tomcat valve."
  [valve-type & options]
  (pallet-type
   ::valve :className (classname-for valve-type valve-classnames) options))

(defn host
  "Define a tomcat host. Options include:
     valves, contexts, aliases and listeners"
  [name app-base & options]
  (pallet-type
   ::host :collections [::valve ::context ::alias ::listener]
   :name name :appBase app-base options))

(defn alias
  "Define a tomcat alias."
  [name]
  (pallet-type ::alias [:name name]))

(defn context
  "Define a tomcat context. Options include: valves, listeners, loader, manager
   realm, resources, resource-links, parameters, environments, transactions
   watched-resources"
  [name & options]
  (pallet-type
   ::context
   :members [::loader :manager ::realm]
   :collections [::valve ::listener ::resource ::resource-link :parameter
                 ::environment ::transaction :watched-resource]
   options))

(defn loader
  "Define a tomcat class loader."
  [classname options]
  (pallet-type ::loader :className classname options))

(defn parameter
  "Define a tomcat parameter. Options are :description and :override."
  [name value & options]
  (pallet-type ::parameters :name name :value value options))

(defn watched-resource
  "Define a tomcat watched resource. Used in a tomcat context."
  [name]
  (pallet-type ::watched-resources [:name name]))

(defn server
  "Define a tomcat server. Accepts server, listener and a global-resources
  form. eg.
     (server :port \"123\" :shutdown \"SHUTDOWNx\"
       (global-resources)
         (service
           (engine \"catalina\" \"host\"
             (valve :request-dumper))
             (connector :port \"8080\" :protocol \"HTTP/1.1\"
                :connectionTimeout \"20000\" :redirectPort \"8443\")))"
  [& options]
  (pallet-type
   ::server
   :members [::global-resources]
   :collections [::listener ::service]
   options))

(defn server-configuration
  "Define a tomcat server.  When a key is not specified, the relevant section
   of the template is output, unmodified.
   Options include:
     :class-name       imlementation class - org.apache.catalina.Server
     :port             shutdown listen port - 8005
     :shutdown         shutdown command string - SHUTDOWN
     ::listeners       vector of listeners, each described as an attribute/value
                       map. The listener can be specified using the ::listener
                       key and one of the listener-classname values, or as
                       a :className key.
     ::services         vector of services
     ::global-resources vector of resources."
  [request server]
  (let [base (parameter/get-for-target request [:tomcat :base])]
    (->
     request
     (directory/directory
      (str base "conf"))
     (remote-file/remote-file
      (str base "conf/server.xml")
      :content (apply
                str (tomcat-server-xml (:node-type request) server))))))
