(ns pallet.plugin
  (:use [chiba.plugin :only [plugins]]))

(defn load-plugins
  "Load pallet plugins"
  []
  (plugins #"pallet.plugin\..*"))
