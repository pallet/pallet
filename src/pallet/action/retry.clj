(ns pallet.action.retry
  "Provides an action that can be repeated if it fails"
  (:require
   [pallet.action :as action]
   [pallet.action.exec-script :as exec-script]
   [pallet.script.lib :as lib]))

(defn loop-until
  [service-name condition max-retries standoff]
  (exec-script/exec-checked-script
   (format "Wait for %s" service-name)
   (group (chain-or (let x 0) true))
   (while (not ~condition)
     (do
       (let x (+ x 1))
       (if (= ~max-retries @x)
         (do
           (println
            ~(format "Timed out waiting for %s" service-name)
            >&2)
           (~lib/exit 1)))
       (println ~(format "Waiting for %s" service-name))
       (sleep ~standoff)))))

(defmacro retry-until
  [{:keys [max-retries standoff service-name]
    :or {max-retries 5 standoff 2}}
   condition]
  (let [service-name (or service-name "retryable")]
    `(loop-until ~service-name ~condition ~max-retries ~standoff)))
