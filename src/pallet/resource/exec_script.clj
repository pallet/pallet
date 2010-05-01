(ns pallet.resource.exec-script
  "Script execution."
  (:use pallet.script
        pallet.stevedore
        [pallet.resource :only [invoke-resource]]
        clojure.contrib.logging))

(defn exec-script*
  [script]
  (script))

(defmacro exec-script [& script]
  `(invoke-resource exec-script* [(fn [] ~@script)]))
