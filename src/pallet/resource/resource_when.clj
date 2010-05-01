(ns pallet.resource.resource-when
  "Conditional resource execution."
  (:use pallet.script
        pallet.stevedore
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

;; This is a macro, so that the condition can be wrapped in a function
;; preventing capture of its literal value, and ensuring that it is
;; specialised on target node
(defmacro resource-when-not
  [condition & resources]
  `(invoke-resource
    exec-when*
    [(fn []
       (script
        (if-not ~condition
          (do (unquote (build-resources [] ~@resources))))))]))
