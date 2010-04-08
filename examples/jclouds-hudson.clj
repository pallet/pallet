(ns jclouds-hudson
  (:use
   [pallet.resource.package :only [package]]
   [pallet.crate.git :only [git]]
   [pallet.crate.tomcat :only [tomcat]]
   [pallet.crate.hudson :only [hudson hudson-plugin hudson-job]]))

(defn jclouds-hudson
  []
  (package "maven2")
  (git)
  (tomcat)
  (hudson)
  (hudson-plugin :git)
  (hudson-job :maven2 "jclouds"
              :scm ["http://github.com/jclouds/jclouds.git"]))
