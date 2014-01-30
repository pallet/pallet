(ns pallet.core.tag
  "Tagging of nodes.

TODO: This should be abstracted at this level, rather than in the
provider as currently?")

;;; # Node state tagging
(ann state-tag-name String)
(def state-tag-name "pallet/state")

(ann read-or-empty-map [String -> (Map Keyword Any)])
(defn read-or-empty-map
  [s]
  (if (blank? s)
    {}
    (assert-type-predicate (read-string s) keyword-map?)))

(ann set-state-for-target [String TargetMap -> nil])
(defn set-state-for-target
  "Sets the boolean `state-name` flag on `target`."
  [state-name target]
  (debugf "set-state-for-target %s" state-name)
  (when (taggable? (:node target))
    (debugf "set-state-for-target taggable")
    (let [current (read-or-empty-map (tag (:node target) state-tag-name))
          val (assoc current (keyword (name state-name)) true)]
      (debugf "set-state-for-target %s %s" state-tag-name (pr-str val))
      (tag! (:node target) state-tag-name (pr-str val)))))

(ann has-state-flag? [String TargetMap -> boolean])
(defn has-state-flag?
  "Return a predicate to test for a state-flag having been set."
  [state-name target]
  (debugf "has-state-flag? %s %s" state-name (id (:node target)))
  (let [v (boolean
           (get
            (read-or-empty-map (tag (:node target) state-tag-name))
            (keyword (name state-name))))]
    (tracef "has-state-flag? %s" v)
    v))
