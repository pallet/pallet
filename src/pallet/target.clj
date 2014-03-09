(ns pallet.target
  "A target is a spec map, with a :node element targeting a
specific node (or some other target)."
  (:refer-clojure :exclude [proxy])
  (:require
   [pallet.core.node :as node :refer [node?]]
   [pallet.tag :as tag]))

;;; # Target accessors
(defmacro defnodefn
  [fname]
  (let [node-sym (symbol "pallet.core.node" (name fname))
        v (resolve node-sym)
        m (meta v)
        fname (vary-meta fname merge (dissoc m :line :file :ns))
        arglists (:arglists m)
        nbodies (count arglists)]
    (when-not v
      (throw (ex-info (str "Could not find the node function " fname) {})))
    `(defn ~fname
       ~@(if (= 1 nbodies)
           (let [args (first arglists)]
             `[[~'target ~@(rest args)]
               (~node-sym (:node ~'target) ~@(rest args))])
           (for [args arglists]
             `([~'target ~@(rest args)]
                 (~node-sym (:node ~'target) ~@(rest args))))))))

(defnodefn os-family)
(defnodefn os-version)
(defnodefn packager)
(defnodefn ssh-port)
(defnodefn primary-ip)
(defnodefn private-ip)
(defnodefn is-64bit?)
(defnodefn arch)
(defnodefn hostname)
(defnodefn running?)
(defnodefn terminated?)
(defnodefn id)
(defnodefn compute-service)
(defnodefn image-user)
(defnodefn user)
(defnodefn hardware)
(defnodefn proxy)
(defnodefn node-address)
(defnodefn tag)
(defnodefn tags)
(defnodefn tag!)
(defnodefn taggable?)
(defnodefn has-base-name?)

(defn node
  "Return the target node."
  [target]
  (:node target))

(defn has-node?
  "Predicate to test whether the target has a target node."
  [target]
  (node? (:node target)))

(defn set-state-flag
  "Sets the boolean `state-name` flag on `target`."
  [target state-name]
  {:pre [(map? target)]}
  (tag/set-state-for-node (:node target) state-name))

(defn has-state-flag?
  "Return a predicate for state-flag set on target."
  [target state-flag]
  (tag/has-state-flag? (:node target) state-flag))
