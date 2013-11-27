(ns pallet.executors
  "Action executors for pallet.

   An action has a :action-type. Known types include :script
   and :fn/clojure.

   An action has a :location, :origin for execution on the node running
   pallet, and :target for the target node.

   The action-type determines how the action should be handled:

   :script - action produces script for execution on remote machine
   :fn/clojure  - action is a function for local execution
   :transfer/to-local - action is a function specifying remote source
                        and local destination.
   :transfer/from-local - action is a function specifying local source
                          and remote destination."
  (:require
   [clojure.tools.logging :as logging]
   [pallet.action :refer [implementation with-action-options]]
   [pallet.action-plan :refer [execute-if map-action-f session-exec-action]]
   [pallet.echo.execute :as echo]
   [pallet.local.execute :as local]
   [pallet.node :refer [primary-ip]]
   [pallet.ssh.execute :as ssh]
   [pallet.stevedore :refer [with-source-line-comments]]))

(require 'pallet.actions.direct)

(defn direct-script
  "Execute the direct action implementation, which returns script or other
  argument data, and metadata."
  [session {:keys [args script-dir] :as action}]
  (with-action-options action           ; allow actions to interrogate options
    (let [{:keys [metadata f]} (implementation action :direct)
          {:keys [action-type location]} metadata
          [script session] (apply
                            f (assoc session :script-dir script-dir) args)]
      (logging/tracef "direct-script %s %s" f (vec args))
      (logging/tracef "direct-script %s" script)
      [script action-type location session])))

(defn default-executor
  "The standard direct executor for pallet. Target actions for localhost
   are executed via shell, rather than via ssh."
  [session action]
  (logging/debugf "default-executor")
  (let [[script action-type location session] (direct-script session action)
        localhost? (fn [session]
                     (let [ip (-> session :server :node primary-ip)]
                       (#{"127.0.0.1"} ip)))]
    (logging/tracef "default-executor %s %s" action-type location)
    (logging/debugf "default-executor script %s" script)
    (case [action-type location]
      [:script :origin] (local/script-on-origin
                         session action action-type script)
      [:script :target] (if (localhost? session)
                          (local/script-on-origin
                           session action action-type script)
                          (ssh/ssh-script-on-target
                           session action action-type script))
      [:fn/clojure :origin] (local/clojure-on-origin session action script)
      [:flow/if :origin] (execute-if session action script)
      [:transfer/from-local :origin] [((last script)) session]
      [:transfer/to-local :origin] (ssh/ssh-to-local session script)
      (throw
       (ex-info
        "No suitable executor found"
        {:type :pallet/no-executor-for-action
         :action action
         :executor 'DefaultExector})))))

(defn force-target-via-ssh-executor
  "Direct executor where target actions are always over ssh."
  [session action]
  (let [[script action-type location session] (direct-script session action)]
    (logging/tracef "force-target-via-ssh-executor %s %s" action-type location)
    (logging/tracef "force-target-via-ssh-executor script %s" script)
    (case [action-type location]
      [:script :origin] (local/script-on-origin
                         session action action-type script)
      [:script :target] (ssh/ssh-script-on-target
                         session action action-type script)
      [:fn/clojure :origin] (local/clojure-on-origin session action script)
      [:flow/if :origin] (execute-if session action script)
      [:transfer/from-local :origin] (ssh/ssh-from-local session script)
      [:transfer/to-local :origin] (ssh/ssh-to-local session script)
      (throw
       (ex-info
        "No suitable executor found"
        {:type :pallet/no-executor-for-action
         :action action
         :executor 'DefaultExector})))))

(defn bootstrap-executor
  [session action]
  (let [[script action-type location session] (direct-script session action)]
    (case [action-type location]
      [:script :target] (echo/echo-bash session script)
      (throw
       (ex-info
        (str "No suitable bootstrap executor found "
             "(local actions are not allowed)")
        {:type :pallet/no-executor-for-action
         :action action
         :executor 'BootstrapExector})))))

(defn echo-executor
  [session action]
  (let [[script action-type location session] (direct-script session action)]
    (logging/tracef
     "echo-executor %s %s %s" (:name action) action-type location)
    (case [action-type location]
      [:script :target] (echo/echo-bash session script)
      [:script :origin] (echo/echo-bash session script)
      [:fn/clojure :origin] (echo/echo-clojure session script)
      [:flow/if :origin] (execute-if session action script)
      [:transfer/from-local :origin] (echo/echo-transfer session script)
      [:transfer/to-local :origin] (echo/echo-transfer session script)
      (throw
       (ex-info
        (format "No suitable echo executor found for %s (%s, %s)"
                (:name action) action-type location)
        {:type :pallet/no-executor-for-action
         :action action
         :executor 'EchoExecutor})))))

(defn action-plan-data
  "Return an action's data."
  [session {:keys [action args blocks] :as action-m}]
  (let [action-symbol (:action-symbol action)
        [script action-type location session] (direct-script session action-m)
        exec-action (session-exec-action session)
        self-fn (fn [b session]
                  (first (map-action-f exec-action b session)))
        blocks (when (= 'pallet.actions-impl/if-action action-symbol)
                 [(self-fn (first blocks) session)
                  (self-fn (second blocks) session)])]
    [(merge
      (-> action-m
          (dissoc :node-value-path)
          (update-in [:action] dissoc :impls))
      {:form `(~action-symbol ~@args
               ~@(when blocks
                   (map #(map :form %) blocks)))
       :script (if (and (sequential? script) (map? (first script)))
                 (update-in script [0] dissoc :summary)
                 script)
       :summary (when (and (sequential? script) (map? (first script)))
                  (:summary (first script)))
       :action-type action-type
       :location location}
      (when blocks
        {:blocks blocks}))
     session]))
