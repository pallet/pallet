(ns pallet.task.vmfest-script
  "A task for running vmfest scripts"
  (:require
   [pallet.compute :as compute]
   [pallet.compute.vmfest :as vmfest]
   [pallet.core :as core]))

(def ^{:dynamic true} *vmfest*)

(defn vmfest-script
  "A task for running vmfest scripts.

       lein pallet vmfest-script filename

   The file should be a clojure script that will be executed. *vmfest* is
   bound to the vmfest compute service"
  [request filename]
  (binding [*vmfest* (compute/compute-service :vmfest)]
    (load filename)))
