(ns pallet.actions.direct
  "Direct execution action implementations.

The :direct implementation of actions is designed to return script
that will be executed on the remote target."
  (:require
   [clojure.tools.logging :refer [debugf tracef]]
   [pallet.action :refer [implementation]]))

;;; Require all implementations
(require
 'pallet.actions.direct.directory
 'pallet.actions.direct.exec-script
 'pallet.actions.direct.file
 'pallet.actions.direct.package
 'pallet.actions.direct.remote-directory
 'pallet.actions.direct.remote-file
 'pallet.actions.direct.rsync
 'pallet.actions.direct.service
 'pallet.actions.direct.user)           ; prevent slamhound removing these

(defn direct-script
  "Execute the direct action implementation, which returns script or other
  argument data, and metadata."
  [{:keys [options args] :as action} state-map]
  (debugf "direct-script args %s" args)
  (let [{:keys [metadata f]} (implementation action :direct)
        script-vec (apply f options state-map args)]
    (tracef "direct-script %s %s" f (vec args))
    (tracef "direct-script %s" script-vec)
    script-vec))
