(ns pallet.actions.direct.exec-script
  "Script execution. Script generation occurs with the correct script context."
  (:use
   [pallet.action :only [implement-action]]
   [pallet.actions :only [exec exec-script*]]))

(implement-action exec :direct
  {:action-type :script :location :target}
  [session
   {:keys [language interpreter version] :or {language :bash} :as options}
   script]
  [[options script] session])

(implement-action exec-script* :direct
  {:action-type :script :location :target}
  [session script]
  [[{:language :bash} script] session])
