(ns pallet.core.executor.echo
  "An action executor over echo"
  (:require
   [pallet.actions.direct :refer [direct-script]]
   [pallet.core.executor.protocols :refer :all]
   [pallet.echo.execute :as echo]
   [pallet.user :refer [user?]]))

(deftype EchoActionExecutor [result-chan]
  ActionExecutor
  (execute [executor target action]
    {:pre [(:node target)]}
    (let [[md script] (direct-script action nil)]
      {:action-meta md
       :script-meta (first script)
       :script (second script)})))

(defn echo-executor
  []
  (EchoActionExecutor. nil))
