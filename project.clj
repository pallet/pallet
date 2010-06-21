(defproject pallet "0.1.1"
  :description "Pallet - provisioning of compute nodes using jclouds"
  :url "http://github.com/hugpduncan/pallet"
  :main pallet.main
  :dependencies [[org.clojure/clojure "1.2.0-master-20100607.150309-85"]
		 [org.clojure/clojure-contrib "1.2.0-20100615.150419-128"]
                 [org.jclouds/jclouds-compute "1.0-20100616.224621-194"]
                 [org.jclouds/jclouds-jsch "1.0-20100616.224621-186"]
                 [org.jclouds/jclouds-log4j "1.0-20100616.224621-194"]
                 [org.jclouds/jclouds-enterprise "1.0-20100616.224621-187"]
                 [org.apache.maven/maven-settings "2.0.10"]
                 [enlive "1.0.0-20100502.112537-18"]
		 [clj-ssh "0.1.0"]
                 [log4j/log4j "1.2.14"]
                 [com.jcraft/jsch "0.1.42"]
                 [jline "0.9.94"]]
  :dev-dependencies [[org.jclouds/jclouds-blobstore "1.0-20100616.224621-188"]
                     [org.jclouds/jclouds-azure "1.0-20100616.224621-185"]
                     [org.jclouds/jclouds-atmos "1.0-20100616.224621-185"]
                     [org.jclouds/jclouds-aws "1.0-20100616.224621-185"]
                     [org.jclouds/jclouds-rackspace "1.0-20100616.224621-185"]
                     [org.jclouds/jclouds-terremark "1.0-20100616.224621-176"]
                     [org.jclouds/jclouds-hostingdotcom "1.0-20100616.224621-176"]
                     [org.jclouds/jclouds-rimuhosting "1.0-20100616.224621-176"]
                     [org.jclouds/jclouds-gogrid "1.0-20100616.224621-175"]
                     [swank-clojure/swank-clojure "1.2.1"]
                     [autodoc "0.7.0"]]
  :repositories [["build.clojure.org" "http://build.clojure.org/releases/"]
		 ["clojars.org" "http://clojars.org/repo/"]
                 ["jclouds" "http://jclouds.googlecode.com/svn/repo"]
                 ["jclouds-snapshot" "http://jclouds.rimuhosting.com/maven2/snapshots"]]
  :autodoc {:name "Pallet"
	    :description "Pallet is used to start provision compute nodes using jclouds and chef."
	    :copyright "Copyright Hugo Duncan 2010. All rights reserved."
	    :web-src-dir "http://github.com/hugoduncan/pallet/blob/"
	    :web-home "http://hugoduncan.github.com/pallet/" })
