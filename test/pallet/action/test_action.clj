(ns pallet.action.test-action
  "Defines a test action"
  (:use
   [pallet.action :as action]))

(action/def-bash-action test-action
  [session]
  (str
   "test-resource:"
   (-> session :server :tag)
   (-> session :server :image)))

(action/def-bash-action test-component
  [session arg]
  (str arg))
