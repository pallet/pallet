(ns pallet.actions.test-actions
  "Actions useful in testing"
  (:require
   [pallet.action :refer [defaction implement-action]]
   [pallet.core.executor.plan
    :refer [action-result replace-action-with-symbol]]
   [pallet.exception :refer [domain-info]]))

(defaction fail
  "An action that always fails."
  [session])

(defn fail* [action-state]
  "echo fail action; exit 1")

(implement-action fail :direct {} {:language :bash} fail*)

(defmethod action-result `fail
  [target action]
  (throw
   (domain-info
    "Fail action"
    {:result (assoc (replace-action-with-symbol action)
               :error {:message "fail action"})})))
