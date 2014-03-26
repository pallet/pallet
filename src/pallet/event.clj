(ns pallet.event
  "Pallet events. Provides ability to hook into pallet event stream."
  (:require
   [pallet.session :refer [event-fn]]))

(defn event
  "Publish a pallet event."
  [session m]
  (when-let [f (event-fn session)]
    (f m)))
