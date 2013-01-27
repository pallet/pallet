(ns pallet.test-executors
  "Action executors for testing pallet"
  (:require
   [clojure.tools.logging :as logging]
   [pallet.echo.execute :as echo]
   [pallet.execute :as execute]
   [pallet.local.execute :as local]
   [pallet.script-builder :as script-builder]
   [pallet.ssh.execute :as ssh])
  (:use
   [pallet.executors :only [direct-script]]))

(defn test-executor
  [session action]
  (let [[script action-type location session] (direct-script session action)]
    (case [action-type location]
      [:script :origin] (local/script-on-origin
                         session action action-type script)
      [:script :target] (local/script-on-origin
                         session action action-type script)
      [:fn/clojure :origin] (local/clojure-on-origin session action)
      (throw
       (ex-info
        "No suitable executor found"
        {:type :pallet/no-executor-for-action
         :action action
         :executor 'TestExector})))))
