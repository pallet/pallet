(ns pallet.resource.remote-file
  "Compatability namespace"
  (:require
   [pallet.action :as action]
   [pallet.action.remote-file :as remote-file]
   [pallet.common.deprecate :as deprecate]))

(deprecate/forward-fns
 pallet.action.remote-file
 set-install-new-files set-force-overwrite with-remote-file transfer-file
 remote-file-action remote-file)

(deprecate/forward-vars
 pallet.action.remote-file
 content-options
 version-options
 ownership-options
 all-options)

(def remote-file* (action/action-fn remote-file/remote-file-action))
