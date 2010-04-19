(ns pallet.crate.tomcat-test
  (:refer-clojure :exclude [alias])
  (:use [pallet.crate.tomcat] :reload-all)
  (:require
   [pallet.template :only [apply-templates]]
   [net.cgrand.enlive-html :as enlive-html]
   [pallet.enlive :as enlive])
  (:use clojure.test
        pallet.test-utils
        [pallet.resource.package :only [package package-manager]]
        [pallet.target :only [with-target-tag]]
        [pallet.resource :only [build-resources]]
        [pallet.stevedore :only [script]]
        [pallet.utils :only [cmd-join]]
        [pallet.core :only [defnode]]))

(deftest tomcat-test
  (is (= "debconf-set-selections <<EOF\ndebconf debconf/frontend select noninteractive\ndebconf debconf/frontend seen false\nEOF\naptitude install -y  tomcat6\n"
         (build-resources [] (tomcat)))))

(deftest classname-for-test
  (let [m {:a "a" :b "b"}]
    (is (= "a" (classname-for :a m)))
    (is (= "b" (classname-for :b m)))
    (is (= "c" (classname-for "c" m)))))

(deftest tomcat-deploy-test
  (is (= "cp file.war /var/lib/tomcat6/webapps/ROOT.war\nchown  tomcat6 /var/lib/tomcat6/webapps/ROOT.war\nchgrp  tomcat6 /var/lib/tomcat6/webapps/ROOT.war\nchmod  600 /var/lib/tomcat6/webapps/ROOT.war\n"
         (build-resources [] (deploy "file.war" nil)))))

(deftest tomcat-undeploy-all-test
  (is (= "rm -r -f /var/lib/tomcat6/webapps/*\n"
        (build-resources [] (undeploy-all)))))

(deftest tomcat-undeploy-test
  (is (= "rm -r -f /var/lib/tomcat6/webapps/ROOT\nrm -f /var/lib/tomcat6/webapps/ROOT.war\nrm -r -f /var/lib/tomcat6/webapps/app\nrm -f /var/lib/tomcat6/webapps/app.war\nrm -r -f /var/lib/tomcat6/webapps/foo\nrm -f /var/lib/tomcat6/webapps/foo.war\n"
        (build-resources [] (undeploy nil :app "foo"))))
  (is (= "" (build-resources [] (undeploy)))))

(deftest tomcat-policy-test
  (is (= "cat > /etc/tomcat6/policy.d/100hudson.policy <<'EOF'\ngrant codeBase \"file:${catalina.base}/webapps/hudson/-\" {\n  permission java.lang.RuntimePermission \"getAttribute\";\n};\nEOF\n"
         (build-resources []
          (policy
           100 "hudson"
           {"file:${catalina.base}/webapps/hudson/-"
            ["permission java.lang.RuntimePermission \"getAttribute\""]})))))

(deftest tomcat-blanket-policy-test
  (is (= "cat > /etc/tomcat6/policy.d/100hudson.policy <<'EOF'\ngrant  {\n  permission java.lang.RuntimePermission \"getAttribute\";\n};\nEOF\n"
         (build-resources []
          (policy
           100 "hudson"
           {nil ["permission java.lang.RuntimePermission \"getAttribute\""]})))))

(deftest tomcat-application-conf-test
  (is (= "cat > /etc/tomcat6/Catalina/localhost/hudson.xml <<'EOF'\n<?xml version='1.0' encoding='utf-8'?>\n<Context docBase=\"/srv/hudson/hudson.war\">\n<Environment name=\"HUDSON_HOME\"/>\n</Context>\nEOF\n"
         (build-resources []
          (application-conf
           "hudson"
           "<?xml version='1.0' encoding='utf-8'?>
<Context docBase=\"/srv/hudson/hudson.war\">
<Environment name=\"HUDSON_HOME\"/>
</Context>")))))

(deftest tomcat-user-test
  (is (= "<decl version=\"1.1\"/><tomcat-users><role rolename=\"r1\"/><role rolename=\"r2\"/><user username=\"u2\" password=\"p2\" roles=\"r1,r2\"/><user username=\"u1\" password=\"p1\" roles=\"r1\"/></tomcat-users>\n"
         (build-resources []
          (user :role "r1" "u1" {:password "p1" :roles ["r1"]})
          (user :role "r2" "u2" {:password "p2" :roles ["r1","r2"]})))))

(deftest extract-member-keys-test
  (are [#{:m} #{:c} [:m 1 :c 2 :c 3 :d 1]] =
       (extract-member-keys [:members [:m] :collections [:c] :m 1 :c 2 :c 3 :d 1]))
  (are [#{:m} #{} [:m 1 :c 2 :c 3 :d 1]] =
       (extract-member-keys [:members [:m] :m 1 :c 2 :c 3 :d 1]))
  (are [#{} #{} [:m 1 :c 2 :c 3 :d 1]] =
       (extract-member-keys [:m 1 :c 2 :c 3 :d 1])))

(deftest extract-nested-maps-test
  (is (= {:d 1
          :c [{:pallet-type :c :v 2} {:pallet-type :c :v 3}]
          :m {:pallet-type :m :v 1}}
         (extract-nested-maps
          [#{:m} #{:c}
           [{:pallet-type :m :v 1}
            {:pallet-type :c :v 2}
            {:pallet-type :c :v 3}
            :d 1]]))))

(deftest pallet-type-test
  (= {:pallet-type :t :u {:pallet-type :u :v 1} :a 1}
     (pallet-type :t :member [:u] [:a 1 {:pallet-type :u :v 1}]))
  (= {:pallet-type :t :a 1}
     (pallet-type :t [:a 1])))

(deftest alias-test
  (is (= {:pallet-type :alias :name "fred"}
         (alias "fred"))))

(deftest connector-test
  (is (= {:pallet-type :connector :port 8443}
         (connector :port 8443))))

(deftest ssl-jsee-connector-test
  (is (= {:pallet-type :connector
          :maxThreads 150 :protocol "HTTP/1.1" :scheme "https"
          :keystorePass "changeit" :sslProtocol "TLS" :clientAuth "false"
          :SSLEnabled "true" :port 8442 :secure "true"
          :keystoreFile "${user.home}/.keystore"}
         (ssl-jsee-connector :port 8442))))

(deftest ssl-apr-connector-test
  (is (= {:pallet-type :connector
          :maxThreads 150 :protocol "HTTP/1.1" :scheme "https"
          :sslProtocol "TLSv1" :clientAuth "optional" :SSLEnabled "true"
          :port 8442 :secure "true"
          :SSLCertificateKeyFile= "/usr/local/ssl/server.pem"
          :SSLCertificateFile "/usr/local/ssl/server.crt"}
         (ssl-apr-connector :port 8442))))

(deftest listener-test
  (is (= {:pallet-type :listener :className "org.apache.catalina.core.JasperListener"}
         (listener :jasper))))

(deftest global-resources-test
  (is (= {:pallet-type :global-resources}
         (global-resources))))

(deftest host-test
  (is (= {:pallet-type :host :name "localhost" :appBase "webapps"
          :valve [{:pallet-type :valve
                    :className "org.apache.catalina.valves.RequestDumperValve"}]
          :alias [{:pallet-type :alias :name "www.domain.com"}]}
         (host "localhost" "webapps"
           (alias "www.domain.com")
             (valve :request-dumper)))))

(deftest service-test
  (is (= {:pallet-type :service
          :connector
          [{:pallet-type :connector :redirectPort "8443" :connectionTimeout "20000"
            :port "8080" :protocol "HTTP/1.1"}]
          :engine {:pallet-type :engine :defaultHost "host" :name "catalina"
                   :valve
                   [{:pallet-type :valve
                     :className "org.apache.catalina.valves.RequestDumperValve"}]}}
         (service
          (engine "catalina" "host"
                  (valve :request-dumper))
          (connector :port "8080" :protocol "HTTP/1.1"
                     :connectionTimeout "20000"
                     :redirectPort "8443")))))

(deftest server-test
  (is (= {:pallet-type :server :port "123", :shutdown "SHUTDOWNx"
          :global-resources {:pallet-type :global-resources}
          :listener
          [{:pallet-type :listener :className "org.apache"}
           {:pallet-type :listener
            :className "org.apache.catalina.core.JasperListener"}]}
         (server
          :port "123" :shutdown "SHUTDOWNx"
          (listener "org.apache")
          (listener :jasper)
          (global-resources))))
  (is (= {:pallet-type :server :port "123" :shutdown "SHUTDOWNx"
          :service
          [{:pallet-type :service
            :connector
            [{:pallet-type :connector :redirectPort "8443"
              :connectionTimeout "20000" :port "8080" :protocol "HTTP/1.1"}]
            :engine
            {:pallet-type :engine :defaultHost "host" :name "catalina"
             :host [{:pallet-type :host, :name "localhost", :appBase "webapps"}]
             :valve [{:pallet-type :valve :className
                       "org.apache.catalina.valves.RequestDumperValve"}]}}]
          :global-resources {:pallet-type :global-resources}}
         (server
          :port "123" :shutdown "SHUTDOWNx"
          (global-resources)
          (service
           (engine "catalina" "host"
                   (valve :request-dumper)
                   (host "localhost" "webapps"))
           (connector :port "8080" :protocol "HTTP/1.1"
                      :connectionTimeout "20000"
                      :redirectPort "8443"))))))


(deftest tomcat-server-xml-test
  (defnode test-node [:ubuntu])
  (is (= "<?xml version='1.0' encoding='utf-8'?>\n<Server shutdown=\"SHUTDOWNx\" port=\"123\">\n  <Listener className=\"org.apache.catalina.core.JasperListener\"></Listener>\n  <Listener className=\"org.apache.catalina.mbeans.ServerLifecycleListener\"></Listener>\n  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\"></Listener>\n  <GlobalNamingResources>\n    <Resource pathname=\"conf/tomcat-users.xml\" factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\" description=\"User database that can be updated and saved\" type=\"org.apache.catalina.UserDatabase\" auth=\"Container\" name=\"UserDatabase\"></Resource>\n  </GlobalNamingResources>\n\n  <Service name=\"Catalina\">\n    <Connector URIEncoding=\"UTF-8\" redirectPort=\"8443\" connectionTimeout=\"20000\" protocol=\"HTTP/1.1\" port=\"8080\"></Connector>\n    <Engine defaultHost=\"localhost\" name=\"Catalina\">\n      <Realm resourceName=\"UserDatabase\" className=\"org.apache.catalina.realm.UserDatabaseRealm\"></Realm>\n\n      <Host xmlNamespaceAware=\"false\" xmlValidation=\"false\" deployOnStartup=\"true\" autoDeploy=\"true\" unpackWARs=\"true\" appBase=\"webapps\" name=\"localhost\">\n\n\t<Valve resolveHosts=\"false\" pattern=\"common\" suffix=\".log\" prefix=\"localhost_access.\" directory=\"logs\" className=\"org.apache.catalina.valves.AccessLogValve\"></Valve>\n      </Host>\n    </Engine>\n  </Service>\n</Server>"
         (apply str (tomcat-server-xml
                      test-node
                      (server :port "123" :shutdown "SHUTDOWNx"))))
      "Listener, GlobalNaminResources and Service should be taken from template")
  (is (= "<?xml version='1.0' encoding='utf-8'?>\n<Server shutdown=\"SHUTDOWNx\" port=\"123\"><GlobalNamingResources></GlobalNamingResources><Listener className=\"org.apache\"></Listener><Listener className=\"org.apache.catalina.core.JasperListener\"></Listener>\n  \n  \n  \n  \n\n  <Service name=\"Catalina\">\n    <Connector URIEncoding=\"UTF-8\" redirectPort=\"8443\" connectionTimeout=\"20000\" protocol=\"HTTP/1.1\" port=\"8080\"></Connector>\n    <Engine defaultHost=\"localhost\" name=\"Catalina\">\n      <Realm resourceName=\"UserDatabase\" className=\"org.apache.catalina.realm.UserDatabaseRealm\"></Realm>\n\n      <Host xmlNamespaceAware=\"false\" xmlValidation=\"false\" deployOnStartup=\"true\" autoDeploy=\"true\" unpackWARs=\"true\" appBase=\"webapps\" name=\"localhost\">\n\n\t<Valve resolveHosts=\"false\" pattern=\"common\" suffix=\".log\" prefix=\"localhost_access.\" directory=\"logs\" className=\"org.apache.catalina.valves.AccessLogValve\"></Valve>\n      </Host>\n    </Engine>\n  </Service>\n</Server>"
         (apply str
                (tomcat-server-xml
                 test-node
                 (server
                  :port "123" :shutdown "SHUTDOWNx"
                  (listener "org.apache")
                  (listener :jasper)
                  (global-resources)))))
      "Listener, GlobalNamingResources and Service should be taken from args")

  (is (= "<?xml version='1.0' encoding='utf-8'?>\n<Server shutdown=\"SHUTDOWNx\" port=\"123\"><GlobalNamingResources><Transaction factory=\"some.transaction.class\"></Transaction><resource></resource><Environment name=\"name\" value=\"1\" type=\"java.lang.Integer\"></Environment></GlobalNamingResources>\n  <Listener className=\"org.apache.catalina.core.JasperListener\"></Listener>\n  <Listener className=\"org.apache.catalina.mbeans.ServerLifecycleListener\"></Listener>\n  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\"></Listener>\n  \n\n  <Service name=\"Catalina\">\n    <Connector URIEncoding=\"UTF-8\" redirectPort=\"8443\" connectionTimeout=\"20000\" protocol=\"HTTP/1.1\" port=\"8080\"></Connector>\n    <Engine defaultHost=\"localhost\" name=\"Catalina\">\n      <Realm resourceName=\"UserDatabase\" className=\"org.apache.catalina.realm.UserDatabaseRealm\"></Realm>\n\n      <Host xmlNamespaceAware=\"false\" xmlValidation=\"false\" deployOnStartup=\"true\" autoDeploy=\"true\" unpackWARs=\"true\" appBase=\"webapps\" name=\"localhost\">\n\n\t<Valve resolveHosts=\"false\" pattern=\"common\" suffix=\".log\" prefix=\"localhost_access.\" directory=\"logs\" className=\"org.apache.catalina.valves.AccessLogValve\"></Valve>\n      </Host>\n    </Engine>\n  </Service>\n</Server>"
         (apply str
                (tomcat-server-xml
                 test-node
                 (server
                  :port "123" :shutdown "SHUTDOWNx"
                  (global-resources
                   (environment "name" 1 Integer)
                   (resource "jdbc/mydb" :sql-data-source :description "my db")
                   (transaction "some.transaction.class"))))))
      "Listener, GlobalNamingResources and Service should be taken from args"))

(deftest server-configuration-test
  (defnode a [])
  (with-target-tag :a
    (is (= "cat > /var/lib/tomcat6/conf/server.xml <<EOF\n<?xml version='1.0' encoding='utf-8'?>\n<Server shutdown=\"SHUTDOWNx\" port=\"123\"><GlobalNamingResources></GlobalNamingResources><Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\"></Listener>\n  \n  \n  \n  \n\n  <Service name=\"Catalina\">\n    <Connector URIEncoding=\"UTF-8\" redirectPort=\"8443\" connectionTimeout=\"20000\" protocol=\"HTTP/1.1\" port=\"8080\"></Connector>\n    <Engine defaultHost=\"localhost\" name=\"Catalina\">\n      <Realm resourceName=\"UserDatabase\" className=\"org.apache.catalina.realm.UserDatabaseRealm\"></Realm>\n\n      <Host xmlNamespaceAware=\"false\" xmlValidation=\"false\" deployOnStartup=\"true\" autoDeploy=\"true\" unpackWARs=\"true\" appBase=\"webapps\" name=\"localhost\">\n\n\t<Valve resolveHosts=\"false\" pattern=\"common\" suffix=\".log\" prefix=\"localhost_access.\" directory=\"logs\" className=\"org.apache.catalina.valves.AccessLogValve\"></Valve>\n      </Host>\n    </Engine>\n  </Service>\n</Server>\nEOF\n"
           (build-resources []
                            (server-configuration
                             (server
                              :port "123" :shutdown "SHUTDOWNx"
                              (listener :global-resources-lifecycle)
                              (global-resources)
                              (service
                               (engine "catalina" "host"
                                       (valve :request-dumper))
                               (connector :port "8080" :protocol "HTTP/1.1"
                                          :connectionTimeout "20000"
                                          :redirectPort "8443")))))))
    (is (= "cat > /var/lib/tomcat6/conf/server.xml <<EOF\n<?xml version='1.0' encoding='utf-8'?>\n<Server shutdown=\"SHUTDOWN\" port=\"8005\">\n  <Listener className=\"org.apache.catalina.core.JasperListener\"></Listener>\n  <Listener className=\"org.apache.catalina.mbeans.ServerLifecycleListener\"></Listener>\n  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\"></Listener>\n  <GlobalNamingResources>\n    <Resource pathname=\"conf/tomcat-users.xml\" factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\" description=\"User database that can be updated and saved\" type=\"org.apache.catalina.UserDatabase\" auth=\"Container\" name=\"UserDatabase\"></Resource>\n  </GlobalNamingResources>\n\n  <Service name=\"Catalina\">\n    <Connector URIEncoding=\"UTF-8\" redirectPort=\"8443\" connectionTimeout=\"20000\" protocol=\"HTTP/1.1\" port=\"8080\"></Connector>\n    <Engine defaultHost=\"localhost\" name=\"Catalina\">\n      <Realm resourceName=\"UserDatabase\" className=\"org.apache.catalina.realm.UserDatabaseRealm\"></Realm>\n\n      <Host xmlNamespaceAware=\"false\" xmlValidation=\"false\" deployOnStartup=\"true\" autoDeploy=\"true\" unpackWARs=\"true\" appBase=\"webapps\" name=\"localhost\">\n\n\t<Valve resolveHosts=\"false\" pattern=\"common\" suffix=\".log\" prefix=\"localhost_access.\" directory=\"logs\" className=\"org.apache.catalina.valves.AccessLogValve\"></Valve>\n      </Host>\n    </Engine>\n  </Service>\n</Server>\nEOF\n"
           (build-resources []
                            (server-configuration
                             (server)))))))

