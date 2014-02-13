(ns pallet.core.middleware
  "Allow decorating how plan functions are executed."
  (:require
   [clojure.core.typed
    :refer [ann ann-form def-alias doseq> fn> for> letfn> inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq
            NonEmptySeqable Seq Seqable]]
   [clojure.tools.logging :as logging :refer [debugf]]
   [pallet.core.api :as api :refer [errors plan-fn]]
   [pallet.core.session :refer [set-executor set-user]]
   [pallet.core.tag :as tag]
   [pallet.node :as node]))

;;; # Middleware aware plan execution
(ann execute [BaseSession Node Fn -> PlanResult])
(defn execute
  "Apply a plan function with metadata to the target node."
  [session node plan-fn]
  (let [{:keys [middleware]} (meta plan-fn)]
    (if middleware
      (middleware session node plan-fn)
      (api/execute session node plan-fn))))

;;; # Admin-user setting middleware
(ann image-user-middleware [-> ExecSettingsFn])
(defn image-user-middleware
  "Returns a middleware for setting the admin user to the image credentials."
  [handler]
  (fn> [session :- BaseSession node :- Node plan-fn :- Fn]
    {:pre [(node/node? node)]}
    (let [user (into {} (filter (inst val Any) (node/image-user node)))
          user (if (or (get user :private-key-path) (get user :private-key))
                 (assoc user :temp-key true)
                 user)]
      (debugf "image-user %s" user)
      (handler (set-user session user) node plan-fn))))

;;; # Phase Execution Functions
(defn execute-one-shot-flag
  "Return a middleware, that will execute a phase on nodes that
  don't have the specified state flag set. On successful completion the nodes
  have the state flag set."
  [handler state-flag]
  (fn execute-one-shot-flag [session node plan-fn]
    {:pre [(node/node? node)]}
    (when-not (tag/has-state-flag? state-flag node)
      (let [result (handler session node plan-fn)]
        (tag/set-state-for-node state-flag node)
        result))))

(defn execute-on-filtered
  "Return a function, that will execute a phase on nodes that
  have the specified state flag set."
  [handler filter-f]
  (logging/tracef "execute-on-filtered")
  (fn execute-on-filtered
    [session node plan-fn]
    {:pre [(node/node? node)]}
    (when (filter-f node)
      (handler session node plan-fn))))

(defn execute-on-flagged
  "Return an action middleware, that will execute a phase on nodes
  that have the specified state flag set."
  [handler state-flag]
  (logging/tracef "execute-on-flagged state-flag %s" state-flag)
  (execute-on-filtered handler #(tag/has-state-flag? state-flag %)))

(defn execute-on-unflagged
  "Return an action middleware, that will execute a phase on nodes
  that have the specified state flag set."
  [handler state-flag]
  (logging/tracef "execute-on-flagged state-flag %s" state-flag)
  (execute-on-filtered
   handler (complement #(tag/has-state-flag? state-flag %))))
