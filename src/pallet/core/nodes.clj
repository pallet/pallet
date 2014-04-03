(ns pallet.core.nodes
 "Functions for returning and filtering nodes"
 (:require
  [pallet.compute.jvm :as jvm]
  [pallet.kb :refer [packager-for-os]]))

(defn localhost
  "Make a node representing the local host. This calls `make-node` with values
   inferred for the local host. Takes options as for `make-node`.

       :name \"localhost\"
       :group-name \"local\"
       :ip \"127.0.0.1\"
       :os-family (pallet.compute.jvm/os-family)"
  ([{:keys [name group-name ip os-family id]
    :or {name "localhost"
         group-name :local
         ip "127.0.0.1"
         os-family (jvm/os-family)
         id "localhost"}
    :as options}]
  {:id id
   :hostname (str (clojure.core/name group-name) "-0")
   :primary-ip ip
   :os-family os-family
   :packager (packager-for-os os-family nil)})
  ([]
     (localhost {})))
