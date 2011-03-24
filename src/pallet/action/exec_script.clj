(ns pallet.action.exec-script
  "Script execution. Script generation occurs with the correct script context."
  (:require
   [pallet.action :as action]
   [pallet.stevedore :as stevedore]))

(def exec-script* (action/bash-action [request script] script))

(defmacro exec-script
  "Execute a bash script remotely"
  [request & script]
  `(exec-script* ~request (stevedore/script ~@script)))

(defmacro exec-checked-script
  "Execute a bash script remotely, throwing if any element of the
   script fails."
  [request name & script]
  `(exec-script* ~request (stevedore/checked-script ~name ~@script)))
