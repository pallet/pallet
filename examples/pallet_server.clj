(ns pallet-server
  (:use
   [pallet.resource.package :only [package]]
   [pallet.crate.git :only [git]])
  (:require
   [pallet.crate.tomcat :as tomcat]
   [pallet.crate.hudson :as hudson]))

(defn ci
  []
  (package "maven2")
  (git)
  (tomcat/tomcat)
  (tomcat/server-configuration (tomcat/server))
  (hudson/tomcat)
  (hudson/plugin :git)
  (hudson/job :maven2 "jclouds"
              :scm ["http://github.com/hugoduncan/pallet.git"]))

