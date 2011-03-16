(ns pallet.resource.resource-when
  "Conditional resource execution."
  (:require
   [pallet.action :as action]
   [pallet.resource.exec-script :as exec-script])
  (:use
   clojure.contrib.logging))

(defmacro resource-when
  [request condition & resources]
  `(->
    ~request
    (action/enter-scope)
    (exec-script/exec-script ("if [" ~condition "]; then"))
    ~@resources
    (exec-script/exec-script "fi")
    (action/leave-scope)))

(defmacro resource-when-not
  [request condition & resources]
  `(->
    ~request
    (action/enter-scope)
    (exec-script/exec-script ("if [ !" ~condition "]; then"))
    ~@resources
    (exec-script/exec-script "fi")
    (action/leave-scope)))
