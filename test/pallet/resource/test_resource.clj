(ns pallet.resource.test-resource
  "Defines a test resource"
  (:use
   [pallet.action :as action]))

(action/def-bash-action test-resource
  [request]
  (str
   "test-resource:"
   (-> request :server :tag)
   (-> request :server :image)))

(action/def-bash-action test-component
  [request arg]
  (str arg))
