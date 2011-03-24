(ns pallet.resource.directory
  "Compatability namespace"
  (:require
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   pallet.script.lib
   [pallet.utils :as utils]))

(utils/forward-to-script-lib rmdir mkdir make-temp-dir)

(utils/forward-fns
 pallet.action.directory
 adjust-directory
 make-directory
 directory
 directories)

(def directory* (action/action-fn directory/directory))
