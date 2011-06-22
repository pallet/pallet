(ns pallet.action.conditional
  "Conditional action execution."
  (:refer-clojure :exclude [when when-not])
  (:require
   [pallet.action :as action]
   [pallet.action.exec-script :as exec-script])
  (:use
   clojure.tools.logging))

(defmacro when
  [session condition & crate-fns-or-actions]
  `(->
    ~session
    (action/enter-scope)
    (exec-script/exec-script ("if [" ~condition "]; then"))
    ~@crate-fns-or-actions
    (exec-script/exec-script "fi")
    (action/leave-scope)))

(defmacro when-not
  [session condition & crate-fns-or-actions]
  `(->
    ~session
    (action/enter-scope)
    (exec-script/exec-script ("if [ !" ~condition "]; then"))
    ~@crate-fns-or-actions
    (exec-script/exec-script "fi")
    (action/leave-scope)))
