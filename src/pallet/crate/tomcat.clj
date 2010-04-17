(ns pallet.crate.tomcat
  "Installation of tomcat"
  (:refer-clojure :exclude [alias])
  (:use
   [pallet.core :only [node-type-for-tag]]
   [pallet.target :only [*target-tag*]]
   [pallet.stevedore :only [script]]
   [pallet.resource :only [defcomponent defresource]]
   [pallet.resource.file :only [heredoc file]]
   [pallet.resource.remote-file :only [remote-file remote-file*]]
   [pallet.resource.exec-script :only [exec-script]]
   [pallet.resource.package :only [package]]
   [pallet.template :only [find-template]]
   [pallet.enlive :only [xml-template xml-emit transform-if deffragment elt]]
   [clojure.contrib.prxml :only [prxml]])
  (:require
    pallet.compat
   [pallet.resource :as resource]
   [pallet.resource.service :as service]
   [net.cgrand.enlive-html :as enlive]))

(pallet.compat/require-contrib)

(def tomcat-config-root "/etc/tomcat6/")
(def tomcat-doc-root "/var/lib/tomcat6/")
(def tomcat-user "tomcat6")
(def tomcat-group "tomcat6")

(defn tomcat-user-name [] tomcat-user)
(defn tomcat-group-name [] tomcat-group)

(defn tomcat
  "Install tomcat"
  [] (package "tomcat6"))

(defmacro with-restart
  [& body]
  ;; restart fails to regenerate security policy cache
  `(do
     (service/service "tomcat6" :action :stop)
     ~@body
     (service/service "tomcat6" :action :start)))

(defn undeploy
  "Removes the named webapp directories, and any war files with the same base names."
  [& app-names]
  (doseq [app-name app-names
          :let [app-name (or app-name "ROOT")
                app-name (if (string? app-name) app-name (name app-name))]]
    (let [exploded-app-dir (str tomcat-doc-root "webapps/" app-name)]
      (exec-script
        (script
          (rm ~exploded-app-dir ~{:r true :f true})
          (rm ~(str exploded-app-dir ".war") ~{:f true}))))))

(defn undeploy-all
  "Removes all deployed war file and exploded webapp directories."
  []
  (exec-script
    (script
      (rm ~(str tomcat-doc-root "webapps/*") ~{:r true :f true}))))

(defn deploy
  "Copies the specified remote .war file to the tomcat server under
   webapps/${app-name}.war.  An app-name of \"ROOT\" or nil will deploy the
   source war file as the / webapp. Options:
   :clear-existing true -- removes the existing exploded ${app-name} directory"
  [warfile app-name & opts]
  (let [opts (apply hash-map opts)
        exploded-app-dir (str tomcat-doc-root "webapps/" (or app-name "ROOT"))
        deployed-warfile (str exploded-app-dir ".war")]
    (remote-file deployed-warfile :remote-file warfile
                 :owner tomcat-user :group tomcat-group :mode 600)
    (when (:clear-existing opts)
      (exec-script
        (script (rm ~exploded-app-dir ~{:r true :f true}))))))

(defn deploy-local-file
  [warfile app-name & opts]
  (let [temp-remote-file (str "pallet-tomcat-deploy-" (java.util.UUID/randomUUID))]
    (remote-file temp-remote-file :local-file warfile)
    (apply deploy temp-remote-file app-name opts)
    (exec-script (script (rm ~temp-remote-file)))))

(defn output-grants [[code-base permissions]]
  (let [code-base (when code-base
                    (format "codeBase \"%s\"" code-base))]
  (format
    "grant %s {\n  %s;\n};" (or code-base "") (string/join ";\n  " permissions))))

(defn policy*
  [number name grants]
  (remote-file*
   (str tomcat-config-root "policy.d/" number name ".policy")
   :content (string/join \newline (map output-grants grants))
   :literal true))

(resource/defcomponent policy
  "Configure tomcat policies.
number - determines sequence i which policies are applied
name - a name for the policy
grants - a map from codebase to sequence of permissions"
  policy* [number name grants])

(defn application-conf*
  [name content]
  (remote-file*
   (str tomcat-config-root "Catalina/localhost/" name ".xml")
   :content content
   :literal true))

(resource/defcomponent application-conf
  "Configure tomcat applications.
name - a name for the policy
content - an xml application context"
  application-conf* [name content])


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
    (users* roles users)))

(resource/defresource user
  "Configure tomcat users.
   options are:

   :role rolename
   username {:password \"pw\" :roles [\"role1\" \"role 2\"]}"
  tomcat-user-args apply-tomcat-user [& options])

(def listener-classnames
     {:apr-lifecycle
      "org.apache.catalina.core.AprLifecycleListener"
      :jasper
      "org.apache.catalina.core.JasperListener"
      :server-lifecycle
      "org.apache.catalina.mbeans.ServerLifecycleListener"
      :global-resources-lifecycle
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
  "Flatten a map, removing the :pallet-type and specified keys"
  [m & dissoc-keys]
  (apply concat (apply dissoc m :pallet-type dissoc-keys)))

(deffragment server-resources-transform
  [global-resources]
  [:Environment] (transform-if (global-resources :environment) nil)
  [:Resource] (transform-if (global-resources :resource) nil)
  [:Transaction] (transform-if (global-resources :transaction) nil)
  [:GlobalNamingResources]
  (enlive/do-> ; ensure we have elements to configure
   (transform-if (global-resources :environment)
                 (enlive/prepend (elt :Environment)))
   (transform-if (global-resources :resource)
                 (enlive/prepend (elt :resource)))
   (transform-if (global-resources :transaction)
                 (enlive/prepend (elt :Transaction))))
  [:Environment]
  (transform-if (global-resources :environment)
   (enlive/clone-for [environment (global-resources :environment)]
                     (apply enlive/set-attr (flatten-map environment))))
  [:Resource]
  (transform-if (global-resources :resource)
   (enlive/clone-for [resource (global-resources :resource)]
                     (apply enlive/set-attr (flatten-map resource))))
  [:Transaction]
  (transform-if (global-resources :transaction)
   (enlive/clone-for [transaction (global-resources :transaction)]
                     (apply enlive/set-attr (flatten-map transaction)))))

(deffragment engine-transform
  [engine]
  [:Host] (transform-if (engine :host) nil)
  [:Valve] (transform-if (engine :valve) nil)
  [:Realm] (transform-if (engine :realm) nil)
  [:Engine]
  (enlive/do-> ; ensure we have elements to configure
   (transform-if (engine :host)
                 (enlive/prepend (elt :Host)))
   (transform-if (engine :valve)
                 (enlive/prepend (elt :Valve)))
   (transform-if (engine :realm)
                 (enlive/prepend (elt :Realm))))
  [:Host]
  (transform-if (engine :host)
                (enlive/clone-for
                 [host (engine :host)]
                 (enlive/do->
                  (apply enlive/set-attr (flatten-map host))
                  (engine-transform (engine :host)))))
  [:Valve]
  (transform-if (engine :valve)
                (enlive/clone-for
                 [valve (engine :valve)]
                 (apply enlive/set-attr (flatten-map valve))))
  [:Realm]
  (transform-if (engine :realm)
                (enlive/set-attr (flatten-map (engine :realm)))))

(deffragment service-transform
  [service]
  [:Connector]
  (enlive/clone-for [connector (service :connector)]
                    (apply enlive/set-attr (flatten-map connector)))
  [:Engine]
  (transform-if (service :engine)
                (enlive/do->
                 (apply enlive/set-attr (flatten-map (service :engine)))
                 (engine-transform (service :engine)))))

(defn tomcat-server-xml
  "Generate server.xml content"
  [node-type server]
  #{:pre [node-type]}
  (xml-emit
   (xml-template
    (path-for *server-file*) node-type [server]
    [:Listener]
    (transform-if (server :listener) nil)
    [:GlobalNamingResources]
    (transform-if (server :global-resources) nil)
    [:Server]
    (enlive/do->
     (transform-if (seq (apply concat (select-keys server [:port :shutdown])))
                   (apply enlive/set-attr
                          (apply concat (select-keys server [:port :shutdown]))))
     (transform-if (server :listener)
                   (enlive/prepend (elt :Listener)))
     (transform-if (server :global-resources)
                   (enlive/prepend
                    (elt :GlobalNamingResources))))
    [:Listener]
    (transform-if (server :listener)
      (enlive/clone-for
       [listener (server :listener)]
       (apply enlive/set-attr (flatten-map listener))))
    [:GlobalNamingResources]
    (transform-if (server :global-resources)
      (server-resources-transform (server :global-resources))))
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
                      (and (map? object) (object :pallet-type)))
        add-member (fn [result object]
                     (if-let [pt (pallet-type object)]
                       (assoc result pt
                              (if (members pt)
                                object
                                (conj (or (result pt) []) object)))
                       result))
        members (reduce add-member {} options)
        options (filter (complement pallet-type) options)]
    (merge members (apply hash-map options))))

(defn extract-options [& options]
  (extract-nested-maps (extract-member-keys options)))

(defmacro pallet-type
  "Create a pallet type-map"
  [type-tag & options]
  `(assoc (apply extract-options ~@options) :pallet-type ~type-tag))

(defn listener
  "Define a tomcat listener. listener-type is a classname or a key from
   listener-classnames. Options are listener-type specific, and match
   the attributes in the tomcat docs."
  [listener-type & options]
  (pallet-type :listener
               :className (classname-for listener-type listener-classnames)
               options))

(defn global-resources
  "Define tomcat resources.
   Options include:
     resources, transactions, environments"
  [& options]
  (pallet-type :global-resources
               :collections [:resource :transaction :environment]
               options))

(defn environment
  "Define tomcat environment variable."
  ([name value type]
     (pallet-type :environment
                  [:name name :value value :type (.getName type)]))
  ([name value type override]
     (pallet-type :environment
                  [:name name :value value :type (.getName type)
                   :override override])))

(defn resource
  "Define tomcat JNDI resource.
   resource-type is a classname, or on of :sql-datasource.
   Options include:"
  [name resource-type & options]
  (pallet-type :resource
               :name name
               :type (classname-for resource-type resource-classnames)
               options))

;; (defmacro defresource
;;   "Define a tomcat JNDI resource"
;;   [name name-string resource-type & options]
;;   `(def ~name (apply jndi-resource ~name-string ~resource-type ~@options)))

(defn transaction
  "Define tomcat transaction factory."
  [factory-classname]
  (pallet-type :transaction [:factory factory-classname]))

(defn service
  "Define a tomcat service"
  [& options]
  (pallet-type :service :members [:engine] :collections [:connector] options))

(defn connector
  "Define a tomcat connector"
  [& options]
  (pallet-type :connector options))

(defn engine
  "Define a tomcat engine. Options are:
     valves, realm, hosts"
  [name default-host & options]
  (pallet-type
   :engine :members [:realm] :collections [:valve :host]
   :name name :defaultHost default-host options))

;; TODO : Create specialised constructors for each realm
(defn realm
  "Define a tomcat realm."
  [realm-type & options]
  (pallet-type
   :realm :className (classname-for realm-type realm-classnames) options))

(defn valve
  "Define a tomcat valve."
  [valve-type & options]
  (pallet-type
   :valve :className (classname-for valve-type valve-classnames) options))

(defn host
  "Define a tomcat host. Options include:
     valves, contexts, aliases and listeners"
  [name app-base & options]
  (pallet-type
   :host :collections [:valve :context :alias :listener]
   :name name :appBase app-base options))

(defn alias
  "Define a tomcat alias."
  [name]
  (pallet-type :alias [:name name]))

(defn context
  "Define a tomcat context. Options include: valves, listeners, loader, manager
   realm, resources, resource-links, parameters, environments, transactions
   watched-resources"
  [name & options]
  (pallet-type
   :context
   :members [:loader :manager :realm]
   :collections [:valve :listener :resource :resource-link :parameter
                 :environment :transaction :watched-resource]
   options))

(defn loader
  "Define a tomcat class loader."
  [classname options]
  (pallet-type :loader :className classname options))

(defn parameter
  "Define a tomcat parameter. Options are :description and :override."
  [name value & options]
  (pallet-type :parameters :name name :value value options))

(defn watched-resource
  "Define a tomcat watched resource. Used in a tomcat context."
  [name]
  (pallet-type :watched-resources [:name name]))

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
   :server
   :members [:global-resources]
   :collections [:listener :service]
   options))

(defn server-configuration*
  "Define a tomcat server.  When a key is not specified, the relevant section
   of the template is output, unmodified.
   Options include:
     :class-name       imlementation class - org.apache.catalina.Server
     :port             shutdown listen port - 8005
     :shutdown         shutdown command string - SHUTDOWN
     :listeners        vector of listeners, each described as an attribute/value map.
                       The listener can be specified using the :listener key and
                       one of the listener-classname values, or as a :className key.
     :services         vector of services
     :global-resources vector of resources."
  [server]
  #{:pre [*target-tag*]}
  (remote-file*
   (str tomcat-doc-root "conf/server.xml")
   :content (apply
             str (tomcat-server-xml
                  (node-type-for-tag *target-tag*) server))))

(resource/defcomponent server-configuration
  "Configure tomcat. Accepts a server definition."
  server-configuration* [server])
