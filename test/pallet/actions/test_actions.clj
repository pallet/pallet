(ns pallet.actions.test-actions
  "Actions useful in testing"
  (:require
   [pallet.action :refer [defaction implement-action]]))

(defaction fail
  "An action that always fails."
  [session])

(defn fail* [action-state]
  "echo fail action; exit 1")
(implement-action fail :direct {} {:language :bash} fail*)
