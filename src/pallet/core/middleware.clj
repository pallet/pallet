(ns pallet.core.middleware
  "Allow decorating how plan functions are executed."
  (:require
   [clojure.core.typed
    :refer [ann ann-form def-alias doseq> fn> for> letfn> inst tc-ignore
            AnyInteger Map Nilable NilableNonEmptySeq
            NonEmptySeqable Seq Seqable]]
   [clojure.tools.logging :as logging]
   [pallet.core.api :as api]
   [pallet.core.session :refer [set-executor set-admin-user]]
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
    (let [user (into {} (filter (inst val Any) (node/image-user node)))
          user (if (or (get user :private-key-path) (get user :private-key))
                 (assoc user :temp-key true)
                 user)]
      (handler (set-admin-user session user) node plan-fn))))

;;; # Phase Execution Functions
(defn execute-one-shot-flag
  "Return a middleware, that will execute a phase on nodes that
  don't have the specified state flag set. On successful completion the nodes
  have the state flag set."
  [handler state-flag]
  (fn [session node plan-fn]
    (when (tag/has-state-flag? state-flag)
      (let [result (handler session node plan-fn)]
        (tag/set-state-for-target state-flag node)
        result))))

(defn execute-on-filtered
  "Return a function, that will execute a phase on nodes that
  have the specified state flag set."
  [handler filter-f]
  (logging/tracef "execute-on-filtered")
  (fn execute-on-filtered
    [session node plan-fn]
    (when (filter-f node)
      (handler session node plan-fn))))

(defn execute-on-flagged
  "Return a middleware, that will execute a phase on nodes that
  have the specified state flag set."
  [handler state-flag]
  (logging/tracef "execute-on-flagged state-flag %s" state-flag)
  (execute-on-filtered handler #(tag/has-state-flag? state-flag %)))

(defn execute-on-unflagged
  "Return a middleware, that will execute a phase on nodes that
  have the specified state flag set."
  [handler state-flag]
  (logging/tracef "execute-on-flagged state-flag %s" state-flag)
  (execute-on-filtered handler (complement #(tag/has-state-flag? state-flag %))))

;;; # Decorator aware plan execution
;; (ann execute [BaseSession Node Fn -> PlanResult])
;; (defn execute
;;   "Apply a plan function with metadata to the target node."
;;   [session node plan-fn]
;;   (let [{:keys [execution-settings-f phase-execution-f]} (meta plan-fn)
;;         session (if execution-settings-f
;;                   (execution-settings-f session node)
;;                   session)]
;;     (if phase-execution-f
;;       ;; TODO determin arguments for phase-execution-f
;;       (phase-execution-f session node plan-fn)
;;       (api/execute session node plan-fn))))

;;; # Execution Settings Functions
;; (ann environment-execution-settings [-> ExecSettingsFn])
;; (defn environment-execution-settings
;;   "Returns execution settings based purely on the environment"
;;   []
;;   (fn> [plan-state :- PlanState
;;         _ :- Node]
;;     (let [user (plan-state/get-scope
;;                 plan-state :action-options :default [:user])
;;           executor (plan-state/get-scope
;;                     plan-state :action-options :default [:executor])]
;;       (debugf "environment-execution-settings %s" plan-state)
;;       (debugf "Env user %s" (obfuscated-passwords (into {} user)))
;;       {:user user :executor executor})))

;; (ann environment-image-execution-settings [-> ExecSettingsFn])
;; (defn environment-image-execution-settings
;;   "Returns execution settings based on the environment and the image user."
;;   []
;;   (fn> [plan-state :- PlanState
;;         node :- Node]
;;     (let [user (into {} (filter (inst val Any) (node/image-user node)))
;;           user (if (or (get user :private-key-path) (get user :private-key))
;;                  (assoc user :temp-key true)
;;                  user)
;;           executor (plan-state/get-scope
;;                     plan-state :action-options :default [:executor])]
;;       (tc-ignore
;;        (debugf "Image-user is %s" (obfuscated-passwords user)))
;;       {:user user :executor executor})))
