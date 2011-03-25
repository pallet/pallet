(ns pallet.resource.test-resource
  "Defines a test resource"
  (:use
   [pallet.action :as action]))

(action/def-bash-action test-resource
  [session]
  (str
   "test-resource:"
   (-> session :server :tag)
   (-> session :server :image)))

(action/def-bash-action test-component
  [session arg]
  (str arg))
