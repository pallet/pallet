(ns pallet.actions.direct.conditional
  "Conditional action execution."
  (:use
   [pallet.action :only [implement-action]])
  (:require
   pallet.actions-impl))

(implement-action pallet.actions-impl/if-action :direct
  {:action-type :flow/if :location :origin}
  [session value]
  [value session])
