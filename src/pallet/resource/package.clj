(ns pallet.resource.package
  (:require
   pallet.action.package
   pallet.script.lib
   [pallet.utils :as utils]))

(utils/forward-to-script-lib
 update-package-list upgrade-all-packages install-package upgrade-package
 remove-package purge-package list-installed-packages debconf-set-selections
 package-manager-non-interactive)

(utils/forward-fns
 pallet.action.package
 package packages package-source package-manager add-scope
 add-centos55-to-amzn-linux add-debian-backports add-epel add-rpmforge
 add-jpackage minimal-packages format-source)
