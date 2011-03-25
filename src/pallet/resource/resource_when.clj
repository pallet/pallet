(ns pallet.resource.resource-when
  "Conditional resource execution."
  (:require
   [pallet.action.conditional :as conditional]
   [pallet.utils :as utils]))

(defmacro resource-when
  [session condition & resources]
  `(do
     (utils/deprecated-macro
      ~&form
      (utils/deprecate-rename
       'pallet.resource.resource-when/resource-when
       'pallet.action.conditional/when))
     (conditional/when ~session ~condition ~@resources)))

(defmacro resource-when-not
  [session condition & resources]
  `(do
     (utils/deprecated-macro
      ~&form
      (utils/deprecate-rename
       'pallet.resource.resource-when/resource-when-not
       'pallet.action.conditional/when-not))
     (conditional/when-not ~session ~condition ~@resources)))
