(ns pallet.resource.resource-when
  "Conditional resource execution."
  (:require
   [pallet.stevedore :as stevedore]
   [pallet.argument :as argument]
   [pallet.resource :as resource]
   [pallet.resource-build :as resource-build]
   [pallet.resource.exec-script :as exec-script])
  (:use
   clojure.contrib.logging))

(defmacro resource-when
  [request condition & resources]
  `(exec-script/exec-script
    ~request
    (if ~condition
      (do (unquote (->
                    (resource-build/produce-phases
                      [(:phase ~request)]
                      ((resource/phase ~@resources) ~request))
                    first))))))

;; This is a macro, so that the condition can be wrapped in a function
;; preventing capture of its literal value, and ensuring that it is
;; specialised on target node
(defmacro resource-when-not
  [request condition & resources]
  `(exec-script/exec-script
    ~request
    (if-not ~condition
      (do (unquote (->
                    (resource-build/produce-phases
                      [(:phase ~request)]
                      ((resource/phase ~@resources) ~request))
                    first))))))
