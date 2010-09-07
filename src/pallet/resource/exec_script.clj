(ns pallet.resource.exec-script
  "Script execution. script generation is delayed until resource application
   time, so that it occurs wirh the correct target."
  (:require
   [pallet.resource :as resource]
   [pallet.stevedore :as stevedore]))

(resource/defresource exec-script*
  (exec-script-fn**
   [request script]
   script))

;; these can only be used within a phase macro
(defmacro exec-script [request & script]
  `(exec-script* ~request (stevedore/script ~@script)))

(defmacro exec-checked-script [request name & script]
  `(exec-script* ~request (stevedore/checked-script ~name ~@script)))
