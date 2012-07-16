(ns pallet.crate.package-repo
  "Package repositories"
  (:use
   [pallet.actions :only [exec-checked-script packages]]
   [pallet.crate :only [def-plan-fn]]
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

(def-plan-fn rebuild-repository
  "Rebuild repository indexes for the repository at path"
  [path]
  (exec-checked-script
   (str "Rebuild repository " path)
   (~rebuild-repo ~path)))

(def-plan-fn repository-packages
  "Install packages required for building repositories"
  []
  (packages :aptitude ["dpkg-dev"]))
