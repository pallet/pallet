(ns pallet.resource.package
  (:require
   pallet.action.package
   pallet.action.package.centos
   pallet.action.package.debian-backports
   pallet.action.package.epel
   pallet.action.package.jpackage
   pallet.action.package.rpmforge
   pallet.script.lib
   [pallet.utils :as utils]))

(utils/forward-to-script-lib
 update-package-list upgrade-all-packages install-package upgrade-package
 remove-package purge-package list-installed-packages debconf-set-selections
 package-manager-non-interactive)

(utils/forward-fns
 pallet.action.package
 package packages package-source package-manager add-scope
 minimal-packages format-source)

(utils/forward-fns pallet.action.package.jpackage add-jpackage)
(utils/forward-fns pallet.action.package.epel add-epel)
(utils/forward-fns pallet.action.package.rpmforge add-rpmforge)
(utils/forward-fns pallet.action.package.debian-backports add-debian-backports)

(defn add-centos55-to-amzn-linux
  {:deprecated "0.5.0"}
  [& args]
  (utils/deprecated
   (utils/deprecate-rename
    'pallet.action.package/add-centos55-to-amzn-linux
    'pallet.action.package.centos/add-repository))
  (apply pallet.action.package.centos/add-repository args))
