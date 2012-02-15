(ns pallet.executors
  "Action executors for pallet.

   An action has a :action-type. Known types include :script/bash
   and :fn/clojure.

   An action has a :location, :origin for execution on the node running
   pallet, and :target for the target node.

   The action-type determines how the action should be handled:

   :script/bash - action produces bash script for execution on remote machine
   :fn/clojure  - action is a function for local execution
   :transfer/to-local - action is a function specifying remote source
                        and local destination.
   :transfer/from-local - action is a function specifying local source
                          and remote destination."
  (:require
   ;; ensure direct implementation is loaded
   pallet.actions.direct
   ;; TODO cleanup the above to a single require
   [clojure.tools.logging :as logging]
   [pallet.echo.execute :as echo]
   [pallet.execute :as execute]
   [pallet.local.execute :as local]
   [pallet.script-builder :as script-builder]
   [pallet.ssh.execute :as ssh])
  (:use
   [pallet.action-plan :only [execute-if stop-execution-on-error]]
   [pallet.action :only [implementation]]
   [slingshot.slingshot :only [throw+]]))


(defn direct-script
  "Execute the direct action implementation, which returns script or other
  argument data, and metadata."
  [session {:keys [args] :as action}]
  (let [{:keys [metadata f]} (implementation action :direct)
        {:keys [action-type location]} metadata
        [script session] (apply f session args)]
    (logging/tracef "direct-script %s %s" f (vec args))
    (logging/tracef "direct-script %s" script)
    [script action-type location session]))

(defn default-executor
  "The standard direct executor for pallet"
  [session action]
  (let [[script action-type location session] (direct-script session action)]
    (logging/tracef "default-executor %s %s" action-type location)
    (logging/tracef "default-executor script %s" script)
    (case [action-type location]
      [:script/bash :origin] (local/bash-on-origin
                              session action action-type script)
      [:script/bash :target] (ssh/ssh-script-on-target
                              session action action-type script)
      [:fn/clojure :origin] (local/clojure-on-origin session script)
      [:flow/if :origin] (execute-if session action script)
      [:transfer/from-local :origin] (ssh/ssh-from-local session script)
      [:transfer/to-local :origin] (ssh/ssh-to-local session script)
      (throw+
       {:type :pallet/no-executor-for-action
        :action action
        :executor 'DefaultExector}
       "No suitable executor found"))))

(defn bootstrap-executor
  [session action]
  (let [[script action-type location session] (direct-script session action)]
    (case [action-type location]
      [:script/bash :target] (echo/echo-bash session script)
      (throw+
       {:type :pallet/no-executor-for-action
        :action action
        :executor 'BootstrapExector}
       (str "No suitable bootstrap executor found "
            "(local actions are not allowed)")))))

(defn echo-executor
  [session action]
  (let [[script action-type location session] (direct-script session action)]
    (logging/tracef
     "echo-executor %s %s %s" (:name action) action-type location)
    (case [action-type location]
      [:script/bash :target] (echo/echo-bash session script)
      [:script/bash :origin] (echo/echo-bash session script)
      [:fn/clojure :origin] (echo/echo-clojure session script)
      [:flow/if :origin] (execute-if session action script)
      [:transfer/from-local :origin] (echo/echo-transfer session script)
      [:transfer/to-local :origin] (echo/echo-transfer session script)
      (throw+
       {:type :pallet/no-executor-for-action
        :action action
        :executor 'EchoExecutor}
       "No suitable echo executor found for %s (%s, %s)"
       (:name action) action-type location))))
