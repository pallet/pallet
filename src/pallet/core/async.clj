(ns pallet.core.async
  "Asynchronous execution of pallet plan functions.")

;; Not sure this is worth having as a wrapper
;; (ann ^:no-check go-execute
;;      [BaseSession TargetMap PlanFn -> (ReadOnlyPort PlanResult)])
;; (defn go-execute
;;   "Execute a plan function on a target asynchronously.

;;   Ensures that the session target is set, and that the script
;;   environment is set up for the target.

;;   Returns a channel, which will yield a result for plan-fn, a map
;;   with `:target`, `:return-value` and `:action-results` keys."
;;   [session target plan-fn]
;;   {:pre [(map? session)(map? target)(:node target)(fn? plan-fn)]}
;;   (go-logged
;;    (execute session target plan-fn)))







(ann ^:no-check action-errors?
  [(Seqable (ReadOnlyPort ActionResult)) -> (Nilable PlanResult)])
(defn action-errors?
  "Check for errors reported by the sequence of channels.  This provides
  a synchronisation point."
  [chans]
  (->> chans
       (mapv (fn> [c :- (ReadOnlyPort ActionResult)]
               (timeout-chan c (* 5 60 1000))))
       async/merge
       (async/reduce
        (fn> [r :- (Nilable PlanResult)
              v :- (Nilable ActionResult)]
          (or r (and (nil? v) {:error {:timeout true}})
              (and (:error v) (select-keys v [:error]))))
        nil)))
