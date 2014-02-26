(ns pallet.test-executors
  "Action executors for testing pallet"
  (:require
   ;; [pallet.executors :refer [direct-script]]
   [pallet.local.execute :as local]))

;; (defn test-executor
;;   [session action]
;;   (let [[script action-type location session] (direct-script session action)]
;;     (case [action-type location]
;;       [:script :origin] (local/script-on-origin
;;                          session action action-type script)
;;       [:script :target] (local/script-on-origin
;;                          session action action-type script)
;;       [:fn/clojure :origin] (local/clojure-on-origin session action)
;;       (throw
;;        (ex-info
;;         "No suitable executor found"
;;         {:type :pallet/no-executor-for-action
;;          :action action
;;          :executor 'TestExector})))))
