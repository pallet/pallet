(ns pallet.crate.package-repo
  "Package repositories"
  (:use
   [pallet.action.exec-script :only [exec-checked-script]]
   [pallet.action.package :only [packages]]
   [pallet.script :only [defscript defimpl]]))

;; https://help.ubuntu.com/community/Repositories/Personal
;; http://odzangba.wordpress.com/2006/10/13/how-to-build-local-apt-repositories/

(defscript rebuild-repo [path])
(defimpl rebuild-repo [#{:apt :aptitude}] [path]
  (cd ~path)
  (pipe
   (dpkg-scanpackages . "/dev/null")
   (gzip "-9c" > Packages.gz))
  (cd -))

(defn rebuild-repository
  "Rebuild repository indexes for the repository at path"
  [session path]
  (exec-checked-script
   session
   (str "Rebuild repository " path)
   (~rebuild-repo ~path)))

(defn repository-packages
  "Install packages required for building repositories"
  [session]
  (packages session :aptitude ["dpkg-dev"]))
