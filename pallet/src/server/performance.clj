(ns server.performance
  (:require
   [pallet.core :as core]
   [pallet.stevedore :as stevedore]
   [pallet.resource.directory :as directory]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.package :as package]
   [pallet.crate.iozone :as iozone]
   [pallet.crate.automated-admin-user :as automated-admin-user]))


(defn iozone-test
  []
  (directory/directory "/var/lib/iozone/")
  (exec-script/exec-script
   (stevedore/script
    ("/usr/local/bin/iozone" -Rb
     ~(str "/var/lib/iozone/" (stevedore/script @(hostname :s true)) ".xls")
     -s "2g" -i 0 -i 1 -i 2 -f "/mnt/testfile" -r "32k" -g "1G"))))

(core/defnode small
  [:ubuntu :X86_64 :smallest :image-name-matches ".*" :os-description-matches "[^J]+9.10[^32]+"]
  :bootstrap [(package/package-manager :update)
              (automated-admin-user/automated-admin-user)]
  :configure [(iozone/iozone)]
  :diskperf  [(iozone-test)])
