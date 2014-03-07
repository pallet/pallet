(ns pallet.core.nodes
 "Functions for returning and filtering nodes"
 (:require
  [pallet.compute.jvm :as jvm]
  ;; [pallet.compute.node-list :as node-list]
  ))

;; (defn localhost
;;   "Returns a node for localhost.  Optionally takes a map as per
;; `pallet.compute.node-list/make-node`."
;;   ([options]
;;      (node-list/make-localhost-node options))
;;   ([]
;;      (localhost {})))

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
   :os-family os-family})
  ([]
     (localhost {})))
