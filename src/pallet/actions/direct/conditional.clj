(ns pallet.actions.direct.conditional
  "Conditional action execution."
  (:require
   [pallet.action :refer [implement-action]]
   [pallet.actions-impl :refer [if-action]]))

(implement-action if-action :direct
  {:action-type :flow/if :location :origin}
  [session value]
  [value session])
