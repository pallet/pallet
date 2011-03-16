(ns pallet.resource.test-resource
  "Defines a test resource"
  (:use
   [pallet.action :as action]))

(action/def-bash-action test-resource
  [request]
  (str
   "test-resource:"
   (:tag (:node-type request))
   (:image (:node-type request))))

(action/def-bash-action test-component
  [request arg]
  (str arg))
