(ns pallet.core.node-os
  "Implementation functions for the core api."
  (:require
   [clojure.tools.logging :refer [debugf warnf]]
   [pallet.compute :refer [packager-for-os]]
   [pallet.core.node :as node]
   [pallet.core.plan-state
    :refer [assoc-settings get-settings plan-state? update-settings]]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.script :refer [with-script-context]]
   [pallet.stevedore :refer [with-script-language]]
   [pallet.utils :refer [maybe-assoc]]))

(defn os-details-map? [x]
  (and (map? x)
       (empty? (dissoc x :os-family :os-version :packager :arch))))

(defn node-os
  "Return the os information in the plan-state for the specified node."
  [node plan-state]
  {:pre [(node/node? node) (or (nil? plan-state) (plan-state? plan-state))]
   :post [(or (nil? %) (os-details-map? %))]}
  (let [os-map (if plan-state
                 (get-settings plan-state (node/id node) :pallet/os {})
                 {})]
    (debugf "node-os-details os-map %s" os-map)
    os-map))

(defn node-os!
  "Set the node os information map"
  [node plan-state os-details]
  {:pre [(or (nil? os-details) (os-details-map? os-details))
         (plan-state? plan-state)]}
  (assoc-settings plan-state (node/id node) :pallet/os os-details {}))

(defn node-os-merge!
  "Merge the os-details into the node os information map"
  [node plan-state os-details]
  {:pre [(or (nil? os-details) (os-details-map? os-details))
         (plan-state? plan-state)]}
  (update-settings plan-state (node/id node) :pallet/os merge os-details {}))

(defn node-os-details
  "Return a node-os details map from the plan-state.  These can be set manually,
  or are set by using the detection phases in pallet.crate.os."
  [node plan-state]
  (let [{:keys [os-family os-version] :as os-map} (node-os node plan-state)
        os-map (update-in
                os-map [:packager]
                (fn [x]
                  (or x
                      (if os-family
                        (packager-for-os os-family os-version)))))]
    os-map))

(defn script-template-for-node
  [node plan-state]
  {:pre [node (node/node? node)]}
  (let [{:keys [os-family os-version packager]} (node-os-details
                                                 node plan-state)
        context (seq (remove
                      nil?
                      [os-family
                       packager
                       (when os-version
                         (keyword
                          (format "%s-%s" (name os-family) os-version)))]))]
    (debugf "Script context: %s" (vec context))
    (when-not context
      (debugf "No script context available: %s %s" node plan-state))
    context))

(defmacro ^{:requires [#'with-script-context #'with-script-language]}
  with-script-for-node
  "Set up the script context for a server"
  [target plan-state & body]
  `(let [node# (:node ~target)]
     (if node#
       (with-script-context (script-template-for-node node# ~plan-state)
         (with-script-language :pallet.stevedore.bash/bash
           ~@body))
       (do
         ~@body))))
