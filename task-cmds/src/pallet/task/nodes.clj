(ns pallet.task.nodes
  "List nodes."
  (:require
   [clojure.pprint :refer [pprint]]
   [pallet.api :refer [print-nodes]]
   [pallet.compute :as compute]
   [pallet.core.node :refer [node-map]]
   [pallet.task-utils :refer [process-args]]))

(def nodes-switches
  [["-f" "--format" "Output nodes in a table [table,edn]" :default "table"]])

(def help
  (str "List all nodes."
       \newline \newline
       (last (process-args "nodes" nil nodes-switches))))

(defn ^{:doc help} nodes
  [request & args]
  (let [[{:keys [format]}] (process-args "nodes" args nodes-switches)
        nodes (compute/nodes (:compute request))]
    (condp = format
      "edn" (pprint (map node-map nodes))
      (print-nodes nodes))))
