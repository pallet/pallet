(ns pallet.resource.lib
  "Routines that can be used in other resources"
  (:require
   pallet.resource.script
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore.script :as script-impl]))

;; Register changed files
(script/defscript file-changed [path])
(script-impl/defimpl file-changed :default [path]
  (assoc! changed_files path 1))

(script/defscript set-flag [path])
(script-impl/defimpl set-flag :default [path]
  (assoc! flags_hash ~(name path) 1))

(script/defscript flag? [path])
(script-impl/defimpl flag? :default [path]
  (get flags_hash ~(name path)))
