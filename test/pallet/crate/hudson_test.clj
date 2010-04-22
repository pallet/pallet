(ns pallet.crate.hudson-test
  (:use [pallet.crate.hudson] :reload-all)
  (:require
   [pallet.template :only [apply-templates]]
   [pallet.core :as core]
   [pallet.target :as target]
   [net.cgrand.enlive-html :as xml])
  (:use clojure.test
        pallet.test-utils
        [pallet.resource.package :only [package package-manager]]
        [pallet.resource :only [build-resources]]
        [pallet.stevedore :only [script]]
        [pallet.utils :only [cmd-join]]))

(deftest hudson-tomcat-test
  (is (= "mkdir -p /var/lib/hudson\nchown  root /var/lib/hudson\nchgrp  tomcat6 /var/lib/hudson\nchmod  775 /var/lib/hudson\nif [ \\( ! -e /var/lib/hudson/hudson.war -o \\( \"680e1525fca0562cfd19552b8d8174e2\" != \"$(md5sum /var/lib/hudson/hudson.war | cut -f1 -d' ')\" \\) \\) ]; then wget -O /var/lib/hudson/hudson.war http://hudson-ci.org/latest/hudson.war;fi\necho MD5 sum is $(md5sum /var/lib/hudson/hudson.war)\ncat > /etc/tomcat6/policy.d/99hudson.policy <<'EOF'\ngrant codeBase \"file:${catalina.base}/webapps/hudson/-\" {\n  permission java.security.AllPermission;\n};\ngrant codeBase \"file:/var/lib/hudson/-\" {\n  permission java.security.AllPermission;\n};\nEOF\ncat > /etc/tomcat6/Catalina/localhost/hudson.xml <<'EOF'\n<?xml version=\"1.0\" encoding=\"utf-8\"?>\n <Context\n privileged=\"true\"\n path=\"/hudson\"\n allowLinking=\"true\"\n swallowOutput=\"true\"\n >\n <Environment\n name=\"HUDSON_HOME\"\n value=\"/var/lib/hudson\"\n type=\"java.lang.String\"\n override=\"false\"/>\n </Context>\nEOF\ncp /var/lib/hudson/hudson.war /var/lib/tomcat6/webapps/hudson.war\nchown  tomcat6 /var/lib/tomcat6/webapps/hudson.war\nchgrp  tomcat6 /var/lib/tomcat6/webapps/hudson.war\nchmod  600 /var/lib/tomcat6/webapps/hudson.war\n"
         (build-resources [] (tomcat-deploy)))))

(deftest determine-scm-type-test
  (is (= :git (determine-scm-type ["http://project.org/project.git"]))))

(deftest normalise-scms-test
  (is (= [["http://project.org/project.git"]]
         (normalise-scms ["http://project.org/project.git"]))))

(deftest output-scm-for-git-test
  (core/defnode n [])
  (is (= "<org.spearce.jgit.transport.RemoteConfig>\n  <string>origin</string>\n  <int>5</int>\n  <string>fetch</string>\n  <string>+refs/heads/*:refs/remotes/origin/*</string>\n  <string>receivepack</string>\n  <string>git-upload-pack</string>\n  <string>uploadpack</string>\n  <string>git-upload-pack</string>\n  <string>url</string>\n  <string>http://project.org/project.git</string>\n  <string>tagopt</string>\n  <string></string>\n</org.spearce.jgit.transport.RemoteConfig>"
         (apply str
          (xml/emit*
           (output-scm-for :git n "http://project.org/project.git" {}))))))

(deftest hudson-job-test
  (core/defnode n [])
  (is (= "mkdir -p /var/lib/hudson/jobs/project\ncat > /var/lib/hudson/jobs/project/config.xml <<EOF\n<?xml version='1.0' encoding='utf-8'?>\n<maven2-moduleset>\n  <actions></actions>\n  <description></description>\n  <logRotator>\n    <daysToKeep>-1</daysToKeep>\n    <numToKeep>-1</numToKeep>\n    <artifactDaysToKeep>-1</artifactDaysToKeep>\n    <artifactNumToKeep>-1</artifactNumToKeep>\n  </logRotator>\n  <keepDependencies>false</keepDependencies>\n  <properties></properties>\n  <scm class=\"hudson.plugins.git.GitSCM\">\n    <remoteRepositories><org.spearce.jgit.transport.RemoteConfig>\n  <string>origin</string>\n  <int>5</int>\n  <string>fetch</string>\n  <string>+refs/heads/*:refs/remotes/origin/*</string>\n  <string>receivepack</string>\n  <string>git-upload-pack</string>\n  <string>uploadpack</string>\n  <string>git-upload-pack</string>\n  <string>url</string>\n  <string>http://project.org/project.git</string>\n  <string>tagopt</string>\n  <string></string>\n</org.spearce.jgit.transport.RemoteConfig>\n      \n    </remoteRepositories>\n    <branches>\n      <hudson.plugins.git.BranchSpec>\n        <name>origin/master</name>\n      </hudson.plugins.git.BranchSpec>\n    </branches>\n    <mergeOptions></mergeOptions>\n    <doGenerateSubmoduleConfigurations>false</doGenerateSubmoduleConfigurations>\n    <submoduleCfg class=\"list\"></submoduleCfg>\n  </scm>\n  <canRoam>true</canRoam>\n  <disabled>false</disabled>\n  <blockBuildWhenUpstreamBuilding>false</blockBuildWhenUpstreamBuilding>\n  <triggers class=\"vector\">\n    <hudson.triggers.SCMTrigger>\n      <spec>*/15 * * * *</spec>\n    </hudson.triggers.SCMTrigger>\n  </triggers>\n  <concurrentBuild>false</concurrentBuild>\n  <rootModule>\n    <groupId>project</groupId>\n    <artifactId>artifact</artifactId>\n  </rootModule>\n  <goals>clojure:test</goals>\n  <mavenOpts>-Dx=y</mavenOpts>\n  <mavenName>base maven</mavenName>\n  <aggregatorStyleBuild>false</aggregatorStyleBuild>\n  <incrementalBuild>false</incrementalBuild>\n  <usePrivateRepository>false</usePrivateRepository>\n  <ignoreUpstremChanges>false</ignoreUpstremChanges>\n  <archivingDisabled>false</archivingDisabled>\n  <reporters></reporters>\n  <publishers></publishers>\n  <buildWrappers></buildWrappers>\n</maven2-moduleset>\nEOF\nmkdir -p /var/lib/hudson\nchown --recursive root /var/lib/hudson\nchgrp --recursive tomcat6 /var/lib/hudson\nchmod --recursive g+w /var/lib/hudson\n"
         (target/with-target-tag :n
           (build-resources []
                            (job
                             :maven2 "project"
                             :maven-opts "-Dx=y"
                             :scm ["http://project.org/project.git"]))))))


(deftest hudson-maven-xml-test
  (core/defnode test-node [:ubuntu])
  (is (= "<?xml version='1.0' encoding='utf-8'?>\n<hudson.tasks.Maven_-DescriptorImpl>\n  <installations>\n    <hudson.tasks.Maven_-MavenInstallation>\n      <name>name</name>\n      <home></home>\n      <properties>\n        <hudson.tools.InstallSourceProperty>\n          <installers>\n            <hudson.tasks.Maven_-MavenInstaller>\n              <id>2.2.0</id>\n            </hudson.tasks.Maven_-MavenInstaller>\n          </installers>\n        </hudson.tools.InstallSourceProperty>\n      </properties>\n    </hudson.tasks.Maven_-MavenInstallation>\n  </installations>\n</hudson.tasks.Maven_-DescriptorImpl>"
         (apply str (hudson-maven-xml
                      test-node
                      [["name" "2.2.0"]])))))

(deftest hudson-maven-test
  (core/defnode n [])
  (is (= "mkdir -p /usr/share/tomcat6/.m2\nchgrp  tomcat6 /usr/share/tomcat6/.m2\nchmod  g+w /usr/share/tomcat6/.m2\ncat > /var/lib/hudson/hudson.tasks.Maven.xml <<EOF\n<?xml version='1.0' encoding='utf-8'?>\n<hudson.tasks.Maven_-DescriptorImpl>\n  <installations>\n    <hudson.tasks.Maven_-MavenInstallation>\n      <name>default maven</name>\n      <home></home>\n      <properties>\n        <hudson.tools.InstallSourceProperty>\n          <installers>\n            <hudson.tasks.Maven_-MavenInstaller>\n              <id>2.2.0</id>\n            </hudson.tasks.Maven_-MavenInstaller>\n          </installers>\n        </hudson.tools.InstallSourceProperty>\n      </properties>\n    </hudson.tasks.Maven_-MavenInstallation>\n  </installations>\n</hudson.tasks.Maven_-DescriptorImpl>\nEOF\nchown  root /var/lib/hudson/hudson.tasks.Maven.xml\nchgrp  tomcat6 /var/lib/hudson/hudson.tasks.Maven.xml\n"
         (target/with-target-tag :n
           (build-resources []
                            (maven "default maven" "2.2.0"))))))
