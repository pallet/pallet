(ns pallet.request-map
  "Compatibility namespace"
  (:require
   [pallet.session :as session]
   [pallet.utils :as utils]))

(utils/forward-fns
 pallet.session
 safe-id phase target-node target-name target-id target-ip os-family
 os-version safe-name packager admin-user group-name)

(defn nodes-in-tag
  "All nodes in the same tag as the target-node, or with the specified tag."
  {:deprecated "0.5.0"}
  ([session]
     (utils/deprecated
      (utils/deprecate-rename
       'pallet.request-map/nodes-in-tag 'pallet.session/nodes-in-group))
     (session/nodes-in-group session))
  ([session group-name] (session/nodes-in-group session group-name)))


(defn tag
  "Tag of the target-node."
  {:deprecated "0.5.0"}
  [session]
  (utils/deprecated
   (utils/deprecate-rename
    'pallet.request-map/tag 'pallet.session/group-name))
  (session/group-name session))
