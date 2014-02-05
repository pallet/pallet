(ns pallet.core.tag
  "Tagging of nodes.

TODO: This should be abstracted at this level, rather than in the
provider as currently? Or should this be left completely at the node
level?"
  (:require
   [clojure.core.typed
    :refer [ann ann-form def-alias doseq> fn> letfn> inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq
            NonEmptySeqable Seq Seqable]]
   [clojure.tools.logging :refer [debugf tracef]]
   [clojure.string :refer [blank?]]
   [pallet.core.types                   ; before any protocols
    :refer [assert-type-predicate keyword-map?]]
   [pallet.node
    :refer [compute-service id image-user group-name node? primary-ip
            tag tag! taggable? terminated?]]))

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
