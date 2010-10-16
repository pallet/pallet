(ns pallet.crate.hudson-test
  (:use pallet.crate.hudson)
  (:require
   [pallet.template :only [apply-templates]]
   [pallet.core :as core]
   [pallet.target :as target]
   [pallet.stevedore :as stevedore]
   [pallet.resource :as resource]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.directory :as directory]
   [pallet.resource.user :as user]
   [pallet.crate.maven :as maven]
   [pallet.crate.tomcat :as tomcat]
   [pallet.utils :as utils]
   [pallet.parameter-test :as parameter-test]
   [net.cgrand.enlive-html :as xml])
  (:use clojure.test
        pallet.test-utils
        [pallet.resource.package :only [package package-manager]]
        [pallet.stevedore :only [script]]))

(def parameters {:host
                 {:id
                  {:tomcat {:owner "tomcat6"
                            :group "tomcat6"
                            :config-path "/etc/tomcat6/"
                            :base "/var/lib/tomcat6/"}}}})
(deftest hudson-tomcat-test
  (is (= (first
          (build-resources
           [:parameters (assoc-in parameters [:host :id :hudson]
                                  {:data-path "/var/lib/hudson"})]
           (directory/directory
            "/var/lib/hudson" :owner "root" :group "tomcat6" :mode "0775")
           (remote-file/remote-file
            "/var/lib/hudson/hudson.war"
            :url "http://hudson-ci.org/latest/hudson.war"
            :md5 nil)
           (tomcat/policy
            99 "hudson"
            {(str "file:${catalina.base}/webapps/hudson/-")
             ["permission java.security.AllPermission"]
             (str "file:/var/lib/hudson/-")
             ["permission java.security.AllPermission"]})
           (tomcat/application-conf
            "hudson"
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>
 <Context
 privileged=\"true\"
 path=\"/hudson\"
 allowLinking=\"true\"
 swallowOutput=\"true\"
 >
 <Environment
 name=\"HUDSON_HOME\"
 value=\"/var/lib/hudson\"
 type=\"java.lang.String\"
 override=\"false\"/>
 </Context>")
           (tomcat/deploy "hudson" :remote-file "/var/lib/hudson/hudson.war")))
         (first
          (build-resources
           [:parameters parameters]
           (tomcat-deploy)
           (parameter-test/parameters-test
            [:host :id :hudson :data-path] "/var/lib/hudson"
            [:host :id :hudson :user] "tomcat6"
            [:host :id :hudson :group] "tomcat6"))))))

(deftest determine-scm-type-test
  (is (= :git (determine-scm-type ["http://project.org/project.git"]))))

(deftest normalise-scms-test
  (is (= [["http://project.org/project.git"]]
         (normalise-scms ["http://project.org/project.git"]))))

(deftest output-scm-for-git-test
  (is (= "<org.spearce.jgit.transport.RemoteConfig>\n  <string>origin</string>\n  <int>5</int>\n  <string>fetch</string>\n  <string>+refs/heads/*:refs/remotes/origin/*</string>\n  <string>receivepack</string>\n  <string>git-upload-pack</string>\n  <string>uploadpack</string>\n  <string>git-upload-pack</string>\n  <string>url</string>\n  <string>http://project.org/project.git</string>\n  <string>tagopt</string>\n  <string></string>\n</org.spearce.jgit.transport.RemoteConfig>"
         (apply str
          (xml/emit*
           (output-scm-for :git {:tag :b :image {:os-family :ubuntu}}
                           "http://project.org/project.git" {}))))))

(deftest hudson-job-test
  (core/defnode n {})
  (is (= (first
          (build-resources
           []
           (directory/directory
            "/var/lib/hudson/jobs/project" :p true
            :owner "root" :group "tomcat6" :mode "0775")
           (remote-file/remote-file
            "/var/lib/hudson/jobs/project/config.xml"
            :content "<?xml version='1.0' encoding='utf-8'?>\n<maven2-moduleset>\n  <actions></actions>\n  <description></description>\n  <logRotator>\n    <daysToKeep>-1</daysToKeep>\n    <numToKeep>-1</numToKeep>\n    <artifactDaysToKeep>-1</artifactDaysToKeep>\n    <artifactNumToKeep>-1</artifactNumToKeep>\n  </logRotator>\n  <keepDependencies>false</keepDependencies>\n  <properties>\n    <com.coravy.hudson.plugins.github.GithubProjectProperty>\n      <projectUrl></projectUrl>\n    </com.coravy.hudson.plugins.github.GithubProjectProperty>\n  </properties>\n  <scm class=\"hudson.plugins.git.GitSCM\">\n    <remoteRepositories><org.spearce.jgit.transport.RemoteConfig>\n  <string>origin</string>\n  <int>5</int>\n  <string>fetch</string>\n  <string>+refs/heads/*:refs/remotes/origin/*</string>\n  <string>receivepack</string>\n  <string>git-upload-pack</string>\n  <string>uploadpack</string>\n  <string>git-upload-pack</string>\n  <string>url</string>\n  <string>http://project.org/project.git</string>\n  <string>tagopt</string>\n  <string></string>\n</org.spearce.jgit.transport.RemoteConfig>\n      \n    </remoteRepositories>\n    <branches>\n      <hudson.plugins.git.BranchSpec>\n        <name>origin/master</name>\n      </hudson.plugins.git.BranchSpec>\n    </branches>\n    <mergeOptions></mergeOptions>\n    <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>\n    <submoduleCfg class=\"list\"></submoduleCfg>\n  </scm>\n  <canRoam>true</canRoam>\n  <disabled>false</disabled>\n  <blockBuildWhenUpstreamBuilding>false</blockBuildWhenUpstreamBuilding>\n  <triggers class=\"vector\">\n    <hudson.triggers.SCMTrigger>\n      <spec>*/15 * * * *</spec>\n    </hudson.triggers.SCMTrigger>\n  </triggers>\n  <concurrentBuild>false</concurrentBuild>\n  <rootModule>\n    <groupId>project</groupId>\n    <artifactId>artifact</artifactId>\n  </rootModule>\n  <goals>clojure:test</goals>\n  \n  <mavenOpts>-Dx=y</mavenOpts>\n  <mavenName>base maven</mavenName>\n  <aggregatorStyleBuild>true</aggregatorStyleBuild>\n  <incrementalBuild>false</incrementalBuild>\n  <usePrivateRepository>false</usePrivateRepository>\n  <ignoreUpstremChanges>false</ignoreUpstremChanges>\n  <archivingDisabled>false</archivingDisabled>\n  <reporters></reporters>\n  <publishers></publishers>\n  <buildWrappers></buildWrappers>\n</maven2-moduleset>"
            :owner "root" :group "tomcat6" :mode "0664")
           (directory/directory
            "/var/lib/hudson"
            :owner "root" :group "tomcat6"
            :mode "g+w"
            :recursive true)))
         (first
          (build-resources
           [:node-type {:image {:os-family :ubuntu}}
            :parameters {:host {:id {:hudson {:data-path "/var/lib/hudson"
                                              :user "tomcat6"
                                              :group "tomcat6"
                                              :owner "root"}}}}]
           (job
            :maven2 "project"
            :maven-opts "-Dx=y"
            :branches ["origin/master"]
            :scm ["http://project.org/project.git"]))))))


(deftest hudson-maven-xml-test
  (core/defnode test-node {:os-family :ubuntu})
  (is (= "<?xml version='1.0' encoding='utf-8'?>\n<hudson.tasks.Maven_-DescriptorImpl>\n  <installations>\n    <hudson.tasks.Maven_-MavenInstallation>\n      <name>name</name>\n      <home>/var/lib/hudson/tools/name</home>\n      \n    </hudson.tasks.Maven_-MavenInstallation>\n  </installations>\n</hudson.tasks.Maven_-DescriptorImpl>"
         (apply str (hudson-maven-xml
                      test-node
                      "/var/lib/hudson"
                      [["name" "2.2.0"]])))))

(deftest hudson-maven-test
  (is (= (first
          (build-resources
           []
           (maven/download
            :maven-home "/var/lib/hudson/tools/default_maven"
            :version "2.2.0"
            :owner "root"
            :group "tomcat6")
           (directory/directory
            "/usr/share/tomcat6/.m2" :group "tomcat6" :mode "g+w")
           (directory/directory
            "/var/lib/hudson" :owner "root" :group "tomcat6" :mode "775")
           (remote-file/remote-file
            "/var/lib/hudson/hudson.tasks.Maven.xml"
            :content "<?xml version='1.0' encoding='utf-8'?>\n<hudson.tasks.Maven_-DescriptorImpl>\n  <installations>\n    <hudson.tasks.Maven_-MavenInstallation>\n      <name>default maven</name>\n      <home>/var/lib/hudson/tools/default_maven</home>\n      \n    </hudson.tasks.Maven_-MavenInstallation>\n  </installations>\n</hudson.tasks.Maven_-DescriptorImpl>"
            :owner "root"
            :group "tomcat6")))
         (first
          (build-resources
           [:node-type {:image {:os-family :ubuntu}}
            :parameters {:host
                         {:id {:hudson {:user "tomcat6" :group "tomcat6"
                                        :owner "root"
                                        :data-path "/var/lib/hudson"}}}}]
           (maven "default maven" "2.2.0"))))))

(deftest plugin-test
  (is (= (first
          (build-resources
           []
           (directory/directory "/var/lib/hudson/plugins")
           (user/user "tomcat6" :action :manage :comment "hudson")
           (remote-file/remote-file
            "/var/lib/hudson/plugins/git.hpi"
            :group "tomcat6" :mode "0664"
            :md5 (-> hudson-plugins :git :md5)
            :url (-> hudson-plugins :git :url))))
         (first
          (build-resources
           [:parameters (assoc-in parameters
                                  [:host :id :hudson]
                                  {:data-path "/var/lib/hudson"
                                   :group "tomcat6"
                                   :user "tomcat6"})]
           (plugin :git))))))

(deftest invocation
  (is (build-resources
       [:parameters parameters]
       (tomcat-deploy)
       (parameter-test/parameters-test
        [:host :id :hudson :user] "tomcat6"
        [:host :id :hudson :group] "tomcat6")
       (maven "name" "2.2.1")
       (job :maven2 "job")
       (plugin :git)))
  (is (build-resources
       [:parameters parameters]
       (tomcat-deploy)
       (tomcat-undeploy))))
