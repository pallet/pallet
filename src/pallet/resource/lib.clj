(ns pallet.resource.lib
  "Routines that can be used in other resources"
  (:require
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]))

;; Register changed files
(script/defscript file-changed [path])
(stevedore/defimpl file-changed :default [path]
  (assoc! changed_files path 1))

(script/defscript set-flag [path])
(stevedore/defimpl set-flag :default [path]
  (assoc! flags_hash ~(name path) 1))

(script/defscript flag? [path])
(stevedore/defimpl flag? :default [path]
  (get flags_hash ~(name path)))
