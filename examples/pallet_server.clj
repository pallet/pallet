(ns pallet-server
  (:use
   [pallet.resource.package :only [package]]
   [pallet.crate.git :only [git]])
  (:require
   [pallet.resource.service :as service]
   [pallet.crate.tomcat :as tomcat]
   [pallet.crate.hudson :as hudson]))

(defn ci
  []
  (package "maven2")
  (git)
  (tomcat/tomcat)
  (service/with-restart "tomcat6"
    (tomcat/server-configuration (tomcat/server))
    (hudson/tomcat-deploy)
    (hudson/plugin :git)
    (hudson/job :maven2 "pallet"
                :scm ["http://github.com/hugoduncan/pallet.git"])))

