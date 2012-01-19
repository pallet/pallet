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
   [pallet.executors :only [direct-script]]
   [slingshot.slingshot :only [throw+]]))

(defn test-executor
  [session action]
  (let [[script action-type location session] (direct-script session action)]
    (case [action-type location]
      [:script/bash :origin] (local/bash-on-origin
                              session action action-type script)
      [:script/bash :target] (local/bash-on-origin
                              session action action-type script)
      [:fn/clojure :origin] (local/clojure-on-origin session action)
      (throw+
       {:type :pallet/no-executor-for-action
        :action action
        :executor 'TestExector}
       "No suitable executor found"))))
