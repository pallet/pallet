(ns pallet.crate.package-repo
  "Package repositories"
  (:require
   [pallet.actions :refer [exec-checked-script package]]
   [pallet.plan :refer [defplan]]
   [pallet.script :refer [defimpl defscript]]
   [pallet.target :refer [packager]]))

;; https://help.ubuntu.com/community/Repositories/Personal
;; http://odzangba.wordpress.com/2006/10/13/how-to-build-local-apt-repositories/

(defscript rebuild-repo [path])
(defimpl rebuild-repo [#{:apt :aptitude}] [path]
  ("cd" ~path)
  (pipe
   ("dpkg-scanpackages" . "/dev/null")
   ("gzip" "-9c" > Packages.gz))
  ("cd" -))

(defplan rebuild-repository
  "Rebuild repository indexes for the repository at path"
  [session path]
  (exec-checked-script
   session
   (str "Rebuild repository " path)
   (~rebuild-repo ~path)))

(defplan repository-packages
  "Install packages required for building repositories"
  [session]
  (case (packager session)
    :aptitude (package session "dpkg-dev")
    :apt (package session "dpkg-dev")
    nil))
