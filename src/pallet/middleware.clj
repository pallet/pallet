(ns pallet.middleware
  "Allow decorating how plan functions are executed."
  (:require
   [clojure.core.typed
    :refer [ann ann-form def-alias doseq> fn> for> letfn> inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq
            NonEmptySeqable Seq Seqable]]
   [clojure.tools.logging :as logging :refer [debugf]]
   [pallet.core.node :as node]
   [pallet.core.types
    :refer [BaseSession PlanExecFn PlanFn PlanResult TargetMap]]
   [pallet.plan :as plan :refer [errors plan-fn]]
   [pallet.session :as session :refer [set-executor set-user]]
   [pallet.tag :as tag]
   [pallet.target :as target]))

;;; # Middleware aware plan execution
(ann execute [BaseSession TargetMap PlanFn -> PlanResult])
(defn execute
  "Apply a plan function with metadata to the target."
  ([session target plan-fn execute-f]
     (let [{:keys [middleware]} (meta plan-fn)]
       (if middleware
         (middleware session target plan-fn)
         (execute-f session target plan-fn))))
  ([session target plan-fn]
     (execute session target plan-fn plan/execute)))

;;; # Admin-user setting middleware
(ann image-user-middleware [PlanExecFn -> PlanExecFn])
(defn image-user-middleware
  "Returns a middleware for setting the admin user to the image credentials."
  [handler]
  (fn> [session :- BaseSession target :- TargetMap plan-fn :- Fn]
    {:pre [(node/node? (target/node target))]}
    (let [user (into {}
                     (filter (inst val Any) (target/image-user target)))
          user (if (or (get user :private-key-path) (get user :private-key))
                 (assoc user :temp-key true)
                 user)
          user (if (some user [:private-key-path :private-key :password])
                 user
                 ;; use credentials from the admin user if no
                 ;; credentials are supplied by the image (but allow
                 ;; image to specify the username)
                 (merge
                  (session/user session)
                  user))]
      (debugf "image-user %s" user)
      (handler (set-user session user) target plan-fn))))

;;; # Phase Execution Functions
(defn execute-one-shot-flag
  "Return a middleware, that will execute a phase on targets that
  don't have the specified state flag set. On successful completion the targets
  have the state flag set."
  [handler state-flag]
  (fn execute-one-shot-flag [session target plan-fn]
    {:pre [(node/node? (target/node target))]}
    (when-not (target/has-state-flag? target state-flag)
      (let [result (handler session target plan-fn)]
        (target/set-state-flag target state-flag)
        result))))

(defn execute-on-filtered
  "Return a function, that will execute a phase on targets that
  return true when applied to the filter-f function."
  [handler filter-f]
  (logging/tracef "execute-on-filtered")
  (fn execute-on-filtered
    [session target plan-fn]
    {:pre [(node/node? (target/node target))]}
    (when (filter-f target)
      (handler session target plan-fn))))

(defn execute-on-flagged
  "Return an action middleware, that will execute a phase on nodes
  that have the specified state flag set."
  [handler state-flag]
  (logging/tracef "execute-on-flagged state-flag %s" state-flag)
  (execute-on-filtered handler #(target/has-state-flag? % state-flag)))

(defn execute-on-unflagged
  "Return an action middleware, that will execute a phase on nodes
  that have the specified state flag set."
  [handler state-flag]
  (logging/tracef "execute-on-flagged state-flag %s" state-flag)
  (execute-on-filtered
   handler (complement #(target/has-state-flag? % state-flag))))
