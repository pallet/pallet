(ns pallet.resource.exec-script
  "Compatability namespace"
  (:require
   [pallet.action.exec-script :as exec-script]
   [pallet.common.deprecate :as deprecate]
   [pallet.utils :as utils]))

(defmacro exec-script
  "Execute a bash script remotely"
  {:deprecated "0.5.0"}
  [session & script]
  `(do
     (deprecate/deprecated-macro
      ~&form
      (deprecate/rename
       'pallet.resource.exec-script/exec-script
       'pallet.action.exec-script/exec-script))
     (exec-script/exec-script ~session ~@script)))

(defmacro exec-checked-script
  "Execute a bash script remotely, throwing if any element of the
   script fails."
  {:deprecated "0.5.0"}
  [session name & script]
  `(do
     (deprecate/deprecated-macro
      ~&form
      (deprecate/rename
       'pallet.resource.exec-script/exec-checked-script
       'pallet.action.exec-script/exec-checked-script))
     (exec-script/exec-checked-script ~session ~name ~@script)))
