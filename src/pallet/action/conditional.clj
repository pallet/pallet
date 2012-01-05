(ns pallet.action.conditional
  "Conditional action execution."
  (:refer-clojure :exclude [when when-not])
  (:require
   [pallet.action.exec-script :as exec-script])
  (:use
   clojure.tools.logging
   [pallet.action-plan :only [if-action enter-scope leave-scope]]
   [pallet.monad :only [phase-pipeline]]))

(defmacro pipeline-when
  [condition & crate-fns-or-actions]
  `(phase-pipeline when-fn {:condition ~(list 'quote condition)}
     (if-action ~condition)
     enter-scope
     ~@crate-fns-or-actions
     leave-scope))

(defmacro pipeline-when-not
  [session condition & crate-fns-or-actions]
  `(phase-pipeline when-not-fn {:condition ~(list 'quote condition)}
     (if-action ~condition)
     enter-scope
     leave-scope
     enter-scope
     ~@crate-fns-or-actions
     leave-scope))
