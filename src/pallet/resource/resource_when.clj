(ns pallet.resource.resource-when
  "Conditional resource execution."
  (:use pallet.script
        pallet.stevedore
        [pallet.utils :only [cmd-join]]
        [pallet.resource :only [invoke-resource build-resources]]
        clojure.contrib.logging))

(defn exec-when*
  [script]
  (script))

(defmacro resource-when [condition & resources]
  `(invoke-resource
    exec-when*
    [(fn []
       (script
        (if ~condition
          (do (unquote (build-resources [] ~@resources))))))]))

(defmacro resource-when-not [condition & resources]
  `(invoke-resource
    exec-when*
    [(fn []
       (script
        (if-not ~condition
          (do (unquote (build-resources [] ~@resources))))))]))
