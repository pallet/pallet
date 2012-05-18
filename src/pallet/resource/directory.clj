(ns pallet.resource.directory
  "Compatability namespace"
  (:require
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.common.deprecate :as deprecate]
   pallet.script.lib
   [pallet.utils :as utils]))

(utils/forward-to-script-lib rmdir mkdir make-temp-dir)

(deprecate/forward-fns
 "0.5.0"
 pallet.action.directory
 adjust-directory
 make-directory
 directory
 directories)

(def directory* (action/action-fn directory/directory))
