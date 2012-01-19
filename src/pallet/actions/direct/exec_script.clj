(ns pallet.actions.direct.exec-script
  "Script execution. Script generation occurs with the correct script context."
  (:use
   [pallet.action :only [implement-action]]
   [pallet.actions :only [exec-script*]]))

(implement-action exec-script* :direct
  {:action-type :script/bash :location :target}
  [session script]
  [script session])
