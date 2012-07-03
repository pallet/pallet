(ns pallet.actions.direct.retry
  "Provides an action that can be repeated if it fails"
  (:require
   [pallet.action :as action]
   [pallet.actions.direct.exec-script :as exec-script]
   [pallet.script.lib :as lib]))
