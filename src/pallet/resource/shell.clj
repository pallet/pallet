(ns pallet.resource.shell
  "General shell functions"
  (:require
   [pallet.utils :as utils]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore.script :as script-impl]
   [pallet.resource.file :as file]
   pallet.resource.script
   [pallet.script :as script]
   [clojure.string :as string]))

(script/defscript exit [value])
(script-impl/defimpl exit :default [value]
  ("exit" ~value))

(script/defscript xargs [script])
(script-impl/defimpl xargs :default
  [script]
  ("xargs" ~script))

(script/defscript which [arg])
(script-impl/defimpl which :default
  [arg]
  ("which" ~arg))
