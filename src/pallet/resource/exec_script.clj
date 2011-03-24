(ns pallet.resource.exec-script
  "Compatability namespace"
  (:require
   [pallet.action.exec-script :as exec-script]
   [pallet.utils :as utils]))

(defmacro exec-script
  "Execute a bash script remotely"
  [request & script]
  `(do
     (utils/deprecated-macro
      ~&form
      (utils/deprecate-rename
       'pallet.resource.exec-script/exec-script
       'pallet.action.exec-script/exec-script))
     (exec-script/exec-script ~request ~@script)))

(defmacro exec-checked-script
  "Execute a bash script remotely, throwing if any element of the
   script fails."
  [request name & script]
  `(do
     (utils/deprecated-macro
      ~&form
      (utils/deprecate-rename
       'pallet.resource.exec-script/exec-checked-script
       'pallet.action.exec-script/exec-checked-script))
     (exec-script/exec-checked-script ~request ~name ~@script)))
