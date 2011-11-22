(ns pallet.executors
  "Action executors for pallet"
  (:require
   [clojure.tools.logging :as logging]
   [pallet.echo.execute :as echo]
   [pallet.execute :as execute]
   [pallet.local.execute :as local]
   [pallet.script-builder :as script-builder]
   [pallet.ssh.execute :as ssh]
   [slingshot.core :as slingshot]))

(defn default-executor
  [session {:keys [action-type location] :as action}]
  (case [action-type location]
    [:script/bash :origin] (local/bash-on-origin session action)
    [:script/bash :target] (ssh/ssh-script-on-target session action)
    [:fn/clojure :origin] (local/clojure-on-origin session action)
    [:transfer/from-local :origin] (ssh/ssh-from-local session action)
    [:transfer/to-local :origin] (ssh/ssh-to-local session action)
    (slingshot/throw+
     {:type :pallet/no-executor-for-action
      :action action
      :executor 'DefaultExector}
     "No suitable executor found")))

(defn bootstrap-executor
  [session {:keys [action-type location] :as action}]
  (case [action-type location]
    [:script/bash :target] (echo/echo-bash session action)
    (slingshot/throw+
     {:type :pallet/no-executor-for-action
      :action action
      :executor 'BootstrapExector}
     "No suitable bootstrap executor found (local actions are not allowed)")))

(defn echo-executor
  [session {:keys [action-type location] :as action}]
  (case [action-type location]
    [:script/bash :target] (echo/echo-bash session action)
    [:script/bash :origin] (echo/echo-bash session action)
    [:fn/clojure :origin] (echo/echo-clojure session action)
    [:transfer/from-local :origin] (echo/echo-transfer session action)
    [:transfer/to-local :origin] (echo/echo-transfer session action)
    (slingshot/throw+
     {:type :pallet/no-executor-for-action
      :action action
      :executor 'EchoExecutor}
     "No suitable echo executor found")))
