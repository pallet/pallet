(ns pallet.plugin (:require
                   [chiba.plugin :refer [plugins]]))

(defn load-plugins
  "Load pallet plugins"
  []
  (let [plugin-namespaces (plugins "pallet.plugin." #".*test.*")]
    (doseq [plugin plugin-namespaces]
      (require plugin)
      (when-let [init (ns-resolve plugin 'init)]
        (init)))
    plugin-namespaces))
