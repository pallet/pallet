(ns pallet.resource.package
  (:require
   pallet.action.package
   pallet.action.package.centos
   pallet.action.package.debian-backports
   pallet.action.package.epel
   pallet.action.package.jpackage
   pallet.action.package.rpmforge
   [pallet.common.deprecate :as deprecate]
   pallet.script.lib
   [pallet.utils :as utils]))

(utils/forward-to-script-lib
 update-package-list upgrade-all-packages install-package upgrade-package
 remove-package purge-package list-installed-packages debconf-set-selections
 package-manager-non-interactive)

(deprecate/forward-fns
 pallet.action.package
 package packages package-source package-manager add-scope
 minimal-packages format-source)

(deprecate/forward-fns pallet.action.package.jpackage add-jpackage)
(deprecate/forward-fns pallet.action.package.epel add-epel)
(deprecate/forward-fns pallet.action.package.rpmforge add-rpmforge)
(deprecate/forward-fns
 pallet.action.package.debian-backports add-debian-backports)

(defn add-centos55-to-amzn-linux
  {:deprecated "0.5.0"}
  [& args]
  (deprecate/deprecated
   (deprecate/rename
    'pallet.action.package/add-centos55-to-amzn-linux
    'pallet.action.package.centos/add-repository))
  (apply pallet.action.package.centos/add-repository args))
