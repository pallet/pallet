(ns pallet.actions.direct.exec-script
  "Script execution. Script generation occurs with the correct script context."
  (:require
   [pallet.action :refer [implement-action]]
   [pallet.actions.decl :refer [exec exec-script*]]))

(implement-action exec :direct
  {:action-type :script :location :target}
  [action-options
   {:keys [language interpreter version] :or {language :bash} :as options}
   script]
  [options script])

(implement-action exec-script* :direct
  {:action-type :script :location :target}
  [action-options script]
  [{:language :bash} script])
