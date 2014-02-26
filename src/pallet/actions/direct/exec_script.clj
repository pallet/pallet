(ns pallet.actions.direct.exec-script
  "Script execution. Script generation occurs with the correct script context."
  (:require
   [pallet.action :refer [implement-action]]
   [pallet.actions.decl :refer [exec exec-script*]]))

(defn exec*
  [_
   {:keys [language interpreter version] :or {language :bash} :as options}
   script]
  [options script])

(implement-action exec :direct {} exec*)

(defn exec-script**
  [_ script]
  script)

(implement-action exec-script* :direct {} {:language :bash} exec-script**)
