(ns pallet.resource.script
  "Select a script-fn implementation for pallet"
  (:require
   [pallet.stevedore :as stevedore]
   [pallet.stevedore.script :as script-impl]))

(stevedore/script-fn-dispatch! script-impl/script-fn-mandatory-dispatch)
