(ns pallet.core.api-impl
  "Implementation functions for the core api."
  (:require
   [clojure.core.typed
    :refer [ann def-alias fn> inst
            Map Nilable NilableNonEmptySeq NonEmptySeqable Seq Vec]]
   [clojure.tools.logging :refer [debugf warnf]]
   [pallet.compute :refer [packager-for-os]]
   [pallet.core.plan-state :refer [get-settings]]
   [pallet.core.protocols :refer [Node]]
   [pallet.core.types
    :refer [assert-type-predicate
            GroupName GroupSpec IncompleteTargetMap Keyword OsDetailsMap
            PlanState TargetMap]]
   [pallet.map-merge :refer [merge-keys]]
   [pallet.node :as node]
   [pallet.script :refer [with-script-context]]
   [pallet.stevedore :refer [with-script-language]]
   [pallet.utils :refer [maybe-assoc]]))

(def-alias KeyAlgorithms (Map Keyword Keyword))
(ann ^:no-check pallet.map-merge/merge-keys
     [KeyAlgorithms (Map Any Any) * -> (Map Any Any)])

(ann merge-spec-algorithm KeyAlgorithms)
(def
  ^{:doc "Map from key to merge algorithm. Specifies how specs are merged."}
  merge-spec-algorithm
  {:phases :merge-phases
   :roles :union
   :group-names :union
   :default-phases :total-ordering})

;; TODO remove :no-check
(ann ^:no-check merge-specs
     [KeyAlgorithms GroupSpec GroupSpec -> GroupSpec])
(defn merge-specs
  "Merge specs using the specified algorithms."
  [algorithms a b]
  (merge-keys algorithms a b))

(ann node-has-group-name? [GroupName -> [Node -> boolean]])
(defn node-has-group-name?
  "Returns a predicate to check if a node has the specified group name."
  {:internal true}
  [group-name]
  (fn> has-group-name? [node :- Node]
    (when-let [node-group (node/group-name node)]
      (= group-name node-group))))

(ann node-in-group? [Node GroupSpec -> boolean])
(defn node-in-group?
  "Check if a node satisfies a group's node-filter."
  {:internal true}
  [node group]
  {:pre [(:group-name group)]}
  ((:node-filter group (node-has-group-name? (:group-name group)))
   node))

;; TODO remove :no-check
(ann ^:no-check node->node-map [(Nilable (NonEmptySeqable GroupSpec))
                                -> [Node -> IncompleteTargetMap]])
(defn node->node-map
  "Build a map entry from a node and a list of groups."
  {:internal true}
  [groups]
  (fn> [node :- Node]
    (when-let [groups (seq (->>
                            groups
                            (filter (fn> [group :- GroupSpec]
                                         (node-in-group? node group)))
                            (map (fn> [group :- GroupSpec]
                                      (assoc-in
                                       group [:group-names]
                                       (set [(:group-name group)]))))))]
      (let [group
            (reduce
             (fn> [target :- GroupSpec group :- GroupSpec]
                  (merge-specs merge-spec-algorithm target group))
             groups)]
       (assoc group :node node)))))

(ann ^:no-check os-details-map? (predicate OsDetailsMap))
(defn os-details-map? [x]
  (and (map? x)
       (empty? (dissoc x :os-family :os-version :packager))))

;; (ann ^:no-check nilable-kw-map? (predicate (U nil (Map Keyword Any))))
;; (defn nilable-kw-map? [x]
;;   (or (nil? x) (map? x) (every? keyword? (keys x))))

;; TODO remove the no-check when building maps in steps is easily typable
(ann ^:no-check target-os-details [TargetMap PlanState -> OsDetailsMap])
(defn target-os-details
  [target plan-state]
  (let [node (get target :node)
        node-id (node/id node)
        node-map (-> {}
                     (maybe-assoc :os-family (node/os-family node))
                     (maybe-assoc :os-version (node/os-version node))
                     (maybe-assoc :packager (node/packager node)))
        detected (get-settings plan-state node-id :pallet/os {})
        warn-diff (fn> [kw :- Keyword
                        from-node :- OsDetailsMap
                        detected :- OsDetailsMap]
                    ;; TODO - switch to keyword invocation when supported
                    ;; in core.typed
                    (let [n (get from-node kw)
                          d (get detected kw)]
                      (when (and n d (not= n d))
                        (warnf
                         "%s mismatch: node returned %s, but %s detected"
                         (name kw) n d))))
        _ (debugf "target-os-details node %s detected %s" node-map detected)
        ;; TODO - rewrite this to use map destructuring when core.typed
        ;; supports it
        combined (merge node-map
                        detected
                        (select-keys target [:packager]))
        ;; {:keys [os-family os-version]} combined
        os-family (get combined :os-family)
        os-version (get combined :os-version)
        combined (update-in
                  combined [:packager]
                  (fn> [x :- (Nilable Keyword)]
                    (or x
                        (if os-family
                          (packager-for-os os-family os-version)))))]
    (warn-diff :os-family node-map detected)
    (warn-diff :os-version node-map detected)
    combined))

;; TODO - remove :no-check when core.type can recognise filtering of nil
(ann ^:no-check script-template-for-node
     [TargetMap PlanState -> (Nilable (NonEmptySeqable Keyword))])
(defn script-template-for-node
  [target plan-state]
  {:pre [target (:node target)]}
  (let [{:keys [os-family os-version packager]} (target-os-details
                                                 target plan-state)
        context (seq (remove
                      nil?
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
  `(let [target# ~target]
     (if (:node target#)
       (with-script-context (script-template-for-node target# ~plan-state)
         (with-script-language :pallet.stevedore.bash/bash
           ~@body))
       (do
         ~@body))))
