(ns pallet.core.group
  "Group functions for adjusting nodes")

;;; ## Calculation of node count adjustments
(def-alias NodeDeltaMap (HMap :mandatory {:actual AnyInteger
                                          :target AnyInteger
                                          :delta AnyInteger}))
(def-alias GroupDelta '[GroupSpec NodeDeltaMap])
(def-alias GroupDeltaSeq (Seqable GroupDelta))

(ann group-delta [TargetMapSeq GroupSpec -> NodeDeltaMap])
(defn group-delta
  "Calculate actual and required counts for a group"
  [targets group]
  (let [existing-count (count targets)
        target-count (:count group ::not-specified)]
    (when (= target-count ::not-specified)
      (throw
       (ex-info
        (format "Node :count not specified for group: %s" group)
        {:reason :target-count-not-specified
         :group group}) (:group-name group)))
    {:actual existing-count
     :target target-count
     :delta (- target-count existing-count)}))

(ann group-deltas [TargetMapSeq (Nilable (Seqable GroupSpec)) -> GroupDeltaSeq])
(defn group-deltas
  "Calculate actual and required counts for a sequence of groups. Returns a map
  from group to a map with :actual and :target counts."
  [targets groups]
  ((inst map GroupDelta GroupSpec)
   (juxt
    (inst identity GroupSpec)
    (fn> [group :- GroupSpec]
      (group-delta (filter
                    (fn> [t :- TargetMap]
                      (node-in-group? (get t :node) group))
                    targets)
                   group)))
   groups))

;; TODO remove no-check when core.typed can handle first, second on Vector*
(ann ^:no-check groups-to-create [GroupDeltaSeq -> (Seq GroupSpec)])
(defn groups-to-create
  "Return a sequence of groups that currently have no nodes, but will have nodes
  added."
  [group-deltas]
  (letfn> [new-group? :- [NodeDeltaMap -> boolean]
           ;; TODO revert to destructuring when supported by core.typed
           (new-group? [delta]
            (and (zero? (get delta :actual)) (pos? (get delta :target))))]
    (->>
     group-deltas
     (filter (fn> [delta :- GroupDelta]
                  (new-group? ((inst second NodeDeltaMap) delta))))
     ((inst map GroupSpec GroupDelta) (inst first GroupSpec))
     ((inst map GroupSpec GroupSpec)
      (fn> [group-spec :- GroupSpec]
           (assoc group-spec :target-type :group))))))

;; TODO remove no-check when core.typed can handle first, second on Vector*
(ann ^:no-check groups-to-remove [GroupDeltaSeq -> (Seq GroupSpec)])
(defn groups-to-remove
  "Return a sequence of groups that have nodes, but will have all nodes
  removed."
  [group-deltas]
  (letfn> [remove-group? :- [NodeDeltaMap -> boolean]
           ;; TODO revert to destructuring when supported by core.typed
           (remove-group? [delta]
            (and (zero? (get delta :target)) (pos? (get delta :actual))))]
    (->>
     group-deltas
     (filter (fn> [delta :- GroupDelta]
                  (remove-group? ((inst second NodeDeltaMap) delta))))
     ((inst map GroupSpec GroupDelta)
      (fn> [group-delta :- GroupDelta]
           (assoc (first group-delta) :target-type :group))))))

;; TODO remove no-check when core.typed can handle first, second on Vector*
(def-alias GroupNodesForRemoval
  (HMap :mandatory
        {:targets (NonEmptySeqable TargetMap)
         :all boolean}))

(ann ^:no-check nodes-to-remove
     [TargetMapSeq GroupDeltaSeq
      -> (Seqable '[GroupSpec GroupNodesForRemoval])])
(defn nodes-to-remove
  "Finds the specified number of nodes to be removed from the given groups.
  Nodes are selected at random. Returns a map from group to a map with
  :servers and :all, where :servers is a sequence of severs to remove, and :all
  is a boolean that is true if all nodes are being removed."
  [targets group-deltas]
  (letfn> [pick-servers :- [GroupDelta
                            -> '[GroupSpec
                                 (HMap :mandatory
                                       {:targets (NonEmptySeqable TargetMap)
                                        :all boolean})]]
           ;; TODO revert to destructuring when supported by core.typed
           (pick-servers [group-delta]
             (let
                 [group ((inst first GroupSpec) group-delta)
                  dm ((inst second NodeDeltaMap) group-delta)
                  target (get dm :target)
                  delta (get dm :delta)]
               (vector
                group
                {:targets (take (- delta)
                              (filter
                               (fn> [target :- TargetMap]
                                    (node-in-group? (get target :node) group))
                               targets))
                 :all (zero? target)})))]
    (into {}
          (->>
           group-deltas
           (filter (fn> [group-delta :- GroupDelta]
                        (when (neg? (:delta (second group-delta)))
                          group-delta)))
           (map pick-servers)))))

;; TODO remove no-check when core.typed can handle first, second on Vector*
(ann ^:no-check nodes-to-add
     [GroupDeltaSeq -> (Seqable '[GroupSpec AnyInteger])])
(defn nodes-to-add
  "Finds the specified number of nodes to be added to the given groups.
  Returns a map from group to a count of servers to add"
  [group-deltas]
  (->>
   group-deltas
   (filter (fn> [group-delta :- GroupDelta]
                (when (pos? (get (second group-delta) :delta))
                  [(first group-delta)
                   (get (second group-delta) :delta)])))))

;;; ## Node creation and removal
;; TODO remove :no-check when core.type understands assoc

;; (ann ^:no-check create-nodes
;;      [Session ComputeService GroupSpec AnyInteger -> (Seq TargetMap)])
;; (defn create-nodes
;;   "Create `count` nodes for a `group`."
;;   [session compute-service group count]
;;   ((inst map TargetMap Node)
;;    (fn> [node :- Node] (assoc group :node node))
;;    (let [targets (run-nodes
;;                   compute-service group count
;;                   (admin-user session)
;;                   nil
;;                   (get-scopes
;;                    (plan-state)
;;                    {:provider (:provider (service-properties compute-service))
;;                     ;; :service ()
;;                     }
;;                    [:provider-options]))]
;;      (add-system-targets session targets)
;;      targets)))

;; (ann remove-nodes [Session ComputeService GroupSpec GroupNodesForRemoval
;;                    -> nil])
;; (defn remove-nodes
;;   "Removes `nodes` from `group`. If `all` is true, then all nodes for the group
;;   are being removed."
;;   [session compute-service group removals]
;;   (let [all (:all removals)
;;         targets (:targets removals)]
;;     (debugf "remove-nodes all %s targets %s" all (vector targets))
;;     (remove-system-targets session targets)
;;     (if all
;;       (destroy-nodes-in-group compute-service (:group-name group))
;;       (doseq> [node :- TargetMap targets]
;;         (destroy-node compute-service (:node node))))))

(ann create-group-nodes
  [Session ComputeService (Seqable '[GroupSpec NodeDeltaMap]) ->
   (Seqable (ReadOnlyPort '[(Nilable (Seqable TargetMap))
                            (Nilable (ErrorMap '[GroupSpec NodeDeltaMap]))]))])
(defn create-group-nodes
  "Create nodes for groups."
  [session compute-service group-counts]
  (debugf "create-group-nodes %s %s" compute-service (vec group-counts))
  (map-async
   (fn> [v :- '[GroupSpec NodeDeltaMap]]
     (create-nodes session compute-service (first v) (:delta (second v))))
   group-counts
   (* 5 60 1000)))

(ann remove-group-nodes
  [Session ComputeService (Seqable '[GroupSpec GroupNodesForRemoval]) -> Any])
(defn remove-group-nodes
  "Removes nodes from groups. `group-nodes` is a map from group to a sequence of
  nodes"
  [session compute-service group-nodes]
  (debugf "remove-group-nodes %s" group-nodes)
  (map-async
   (fn> [x :- '[GroupSpec GroupNodesForRemoval]]
     (remove-nodes session compute-service (first x) (second x)))
   group-nodes
   (* 5 60 1000)))
