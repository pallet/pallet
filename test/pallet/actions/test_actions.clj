(ns pallet.actions.test-actions
  "Actions useful in testing"
  (:require
   [pallet.action :refer [defaction implement-action]]))

(defaction fail
  "An action that always fails."
  [session])

(implement-action fail :direct
  {:action-type :script :location :target}
  []
  [{:language :bash} "echo fail action; exit 1"])
