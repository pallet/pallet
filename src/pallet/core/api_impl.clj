(ns pallet.core.api-impl
  "Implementation functions for the core api."
  (:require
   [clojure.tools.logging :refer [debugf warnf]]
   [pallet.compute :refer [packager-for-os]]
   [pallet.core.plan-state :refer [get-settings]]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.node :as node]
   [pallet.script :refer [with-script-context]]
   [pallet.stevedore :refer [with-script-language]]
   [pallet.utils :refer [maybe-assoc]]))

(def
  ^{:doc "Map from key to merge algorithm. Specifies how specs are merged."}
  merge-spec-algorithm
  {:phases :merge-phases
   :roles :union
   :group-names :union
   :default-phases :total-ordering})

(defn merge-specs
  "Merge specs using the specified algorithms."
  [algorithms a b]
  (merge-keys algorithms a b))

(defn node-has-group-name?
  "Returns a predicate to check if a node has the specified group name."
  {:internal true}
  [group-name]
  (fn has-group-name? [node]
    (when-let [node-group (node/group-name node)]
      (= group-name (keyword (name node-group))))))

(defn node-in-group?
  "Check if a node satisfies a group's node-filter."
  {:internal true}
  [node group]
  {:pre [(:group-name group)]}
  ((:node-filter group (node-has-group-name? (:group-name group)))
   node))

(defn node->node-map
  "Build a map entry from a node and a list of groups."
  {:internal true}
  [groups]
  (fn [node]
    (when-let [groups (seq (->>
                            groups
                            (filter (partial node-in-group? node))
                            (map #(assoc-in % [:group-names]
                                            (set [(:group-name %)])))))]
      (reduce
       (partial merge-specs merge-spec-algorithm)
       {:node node}
       groups))))

(defn target-os-details
  [target plan-state]
  (let [node (:node target)
        node-id (node/id node)
        node-map (-> {}
                     (maybe-assoc :os-family (node/os-family node))
                     (maybe-assoc :os-version (node/os-version node))
                     (maybe-assoc :packager (node/packager node)))
        detected (select-keys (get-settings plan-state node-id :pallet/os {})
                              [:os-family :os-version])
        warn-diff (fn [key from-node detected]
                    (let [n (key from-node)
                          d (key detected)]
                      (when (and n d (not= n d))
                        (warnf "%s mismatch: node returned %s, but %s detected"
                               (name key) n d))))
        _ (debugf "target-os-details node %s detected %s" node-map detected)
        {:keys [os-family os-version] :as combined} (merge node-map
                                                           detected
                                                           (select-keys
                                                            target [:packager]))
        combined (update-in combined [:packager]
                            #(or %
                                 (when os-family
                                   (packager-for-os os-family os-version))))]
    (warn-diff :os-family node-map detected)
    (warn-diff :os-version node-map detected)
    combined))

(defn script-template-for-node
  [target plan-state]
  {:pre [target (:node target)]}
  (let [{:keys [os-family os-version packager]} (target-os-details
                                                 target plan-state)
        context (seq (filter
                      identity
                      [os-family
                       packager
                       (when os-version
                         (keyword
                          (format "%s-%s" (name os-family) os-version)))]))]
    (debugf "Script context: %s" (vec context))
    (when-not context
      (debugf "No script context available: %s %s" target plan-state))
    context))

(defmacro ^{:requires [#'with-script-context #'with-script-language]}
  with-script-for-node
  "Set up the script context for a server"
  [target plan-state & body]
  `(with-script-context (script-template-for-node ~target ~plan-state)
     (with-script-language :pallet.stevedore.bash/bash
       ~@body)))
