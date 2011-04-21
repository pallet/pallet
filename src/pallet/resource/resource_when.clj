(ns pallet.resource.resource-when
  "Conditional resource execution."
  (:require
   [pallet.action.conditional :as conditional]
   [pallet.common.deprecate :as deprecate]
   [pallet.utils :as utils]))

(defmacro resource-when
  {:deprecated "0.5.0"}
  [session condition & resources]
  `(do
     (deprecate/deprecated-macro
      ~&form
      (deprecate/rename
       'pallet.resource.resource-when/resource-when
       'pallet.action.conditional/when))
     (conditional/when ~session ~condition ~@resources)))

(defmacro resource-when-not
  {:deprecated "0.5.0"}
  [session condition & resources]
  `(do
     (deprecate/deprecated-macro
      ~&form
      (deprecate/rename
       'pallet.resource.resource-when/resource-when-not
       'pallet.action.conditional/when-not))
     (conditional/when-not ~session ~condition ~@resources)))
