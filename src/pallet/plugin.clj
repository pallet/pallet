(ns pallet.plugin
  (:use
   [chiba.plugin :only [plugins]]
   [pallet.monad :only [session-pipeline]]))

;; (defn load-plugins
;;   "Load pallet plugins"
;;   []
;;   (let [plugin-namespaces (plugins "pallet.plugin." #".*test.*")]
;;     (doseq [plugin plugin-namespaces]
;;       (require plugin))
;;     plugin-namespaces))
