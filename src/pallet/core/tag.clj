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
   [pallet.node :refer [id image-user tag tag! taggable?]]))

;;; # Node state tagging
(ann state-tag-name String)
(def state-tag-name "pallet/state")

(ann read-or-empty-map [String -> (Map Keyword Any)])
(defn read-or-empty-map
  [s]
  (if (blank? s)
    {}
    (assert-type-predicate (read-string s) keyword-map?)))

(ann set-state-for-node [Node String -> nil])
(defn set-state-for-node
  "Sets the boolean `state-name` flag on `node`."
  [node state-name]
  {:pre [(node? node)]}
  (debugf "set-state-for-node %s" state-name)
  (when (taggable? node)
    (debugf "set-state-for-node taggable")
    (let [current (read-or-empty-map (tag node state-tag-name))
          val (assoc current (keyword (name state-name)) true)]
      (debugf "set-state-for-node %s %s" state-tag-name (pr-str val))
      (tag! node state-tag-name (pr-str val)))))

(ann has-state-flag? [Node String -> boolean])
(defn has-state-flag?
  "Return a predicate to test for a state-flag having been set."
  [node state-name]
  {:pre [(node? node)]}
  (debugf "has-state-flag? %s %s" state-name (id node))
  (let [v (boolean
           (get
            (read-or-empty-map (tag node state-tag-name))
            (keyword (name state-name))))]
    (tracef "has-state-flag? %s" v)
    v))
