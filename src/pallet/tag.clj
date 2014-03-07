(ns pallet.tag
  "Tagging of nodes."
  (:require
   [clojure.tools.logging :refer [debugf tracef]]
   [clojure.string :refer [blank?]]
   [pallet.core.node :refer [id image-user node? tag tag! taggable?]]))

;;; # Node state tagging
(def state-tag-name "pallet/state")

(defn read-or-empty-map
  [s]
  (if (blank? s)
    {}
    (read-string s)))

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
