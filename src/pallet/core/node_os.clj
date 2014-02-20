(ns pallet.core.node-os
  "Implementation functions for the core api."
  (:require
   [clojure.core.typed
    :refer [ann def-alias fn> inst
            Map Nilable NilableNonEmptySeq NonEmptySeqable Seq Vec]]
   [pallet.core.types
    :refer [assert-type-predicate
            GroupName GroupSpec IncompleteGroupTargetMap Keyword OsDetailsMap
            PlanState TargetMap]]
   [clojure.tools.logging :refer [debugf warnf]]
   [pallet.compute :refer [packager-for-os]]
   [pallet.compute.protocols :refer [Node]]
   [pallet.core.plan-state :refer [assoc-settings get-settings plan-state?]]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.node :as node]
   [pallet.script :refer [with-script-context]]
   [pallet.stevedore :refer [with-script-language]]
   [pallet.utils :refer [maybe-assoc]]))

;; TODO remove the no-check
(ann ^:no-check os-details-map? (predicate OsDetailsMap))
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
  "Set the node os infor map"
  [node plan-state os-details]
  {:pre [(or (nil? os-details) (os-details-map? os-details))]}
  (assoc-settings plan-state (node/id node) :pallet/os os-details {}))

;; TODO remove the no-check when building maps in steps is easily typable
(ann ^:no-check target-os-details [TargetMap PlanState -> OsDetailsMap])
(defn node-os-details
  [node plan-state]
  (let [{:keys [os-family os-version] :as os-map} (node-os node plan-state)
        os-map (update-in
                  os-map [:packager]
                  (fn> [x :- (Nilable Keyword)]
                    (or x
                        (if os-family
                          (packager-for-os os-family os-version)))))]
    os-map))

;; TODO - remove :no-check when core.type can recognise filtering of nil
(ann ^:no-check script-template-for-node
     [TargetMap PlanState -> (Nilable (NonEmptySeqable Keyword))])
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
