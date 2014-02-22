(ns pallet.target
  "A target-map is a server-spec, with a :node element targeting a
specific node (or some other target)."
  (:require
   [pallet.compute :as compute :refer [packager-for-os]]
   [pallet.core.node-os :refer [node-os node-os-merge!]]
   [pallet.node :as node :refer [node? node-map]]
   [pallet.session :as session :refer [plan-state target target-session?]]
   [pallet.tag :as tag]))

;;; # Target accessors
(defmacro defnodefn
  [fname]
  (let [node-sym (symbol "pallet.node" (name fname))
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

(defnodefn ssh-port)
(defnodefn primary-ip)
(defnodefn private-ip)
(defnodefn is-64bit?)
(defnodefn hostname)
(defnodefn running?)
(defnodefn terminated?)
(defnodefn id)
(defnodefn compute-service)
(defnodefn image-user)
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

(defn set-state-flag
  "Sets the boolean `state-name` flag on `target`."
  [target state-name]
  {:pre [(map? target)]}
  (tag/set-state-for-node (node target) state-name))

(defn has-state-flag?
  "Return a predicate for state-flag set on target."
  [target state-flag]
  (tag/has-state-flag? (:node target) state-flag))


(defn os-map
  [session]
  {:pre [(target-session? session)]}
  (node-os (node (target session)) (plan-state session)))

(defn os-family
  "OS-Family of the target-node."
  [session]
  {:pre [(target-session? session)]}
  (or
   (-> session :target :override :os-family)
   (:os-family (os-map session))))

(defn os-version
  "OS-Family of the target-node."
  [session]
  {:pre [(target-session? session)]}
  (or
   (-> session :target :override :os-version)
   (:os-version (os-map session))))

(defn packager
  "Packager of the target-node."
  [session]
  {:pre [(target-session? session)]}
  (or
   (-> session :target :override :packager)
   (packager-for-os (os-family session) (os-version session))))

(defn admin-user
  "Admin-user of the target-node."
  [session]
  {:pre [(target-session? session)]}
  (or
   (-> session :target :override :user)
   (-> session :execution-state :user)))

(defn admin-group
  "User that remote commands are run under"
  [session]
  (compute/admin-group
   (os-family session)
   (os-version session)))

(defn set-target
  "Set the target for the session"
  [session target]
  (when (:node target)
    (when-let [plan-state (plan-state session)]
      (node-os-merge!
       (:node target) plan-state
       (select-keys (:override target) [:os-family :os-version :packager]))))
  (session/set-target session target))

(defn effective-username
  "Return the effective username."
  [session]
  {:post [%]}
  (or
   (-> session :action :sudo-user)
   (-> session :environment :user :sudo-user)
   (-> session :environment :user :username)))
