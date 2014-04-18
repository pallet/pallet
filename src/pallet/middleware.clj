(ns pallet.middleware
  "Allow decorating how plan functions are executed."
  (:require
   [taoensso.timbre :as logging :refer [debugf]]
   [pallet.action-options :refer [with-action-options]]
   [pallet.plan :as plan :refer [plan-errors plan-fn]]
   [pallet.session :as session :refer [set-executor set-user]]
   [pallet.tag :as tag]
   [pallet.node :as node]))

;;; # Admin-user setting middleware
(defn image-user-middleware
  "Returns a middleware for setting the admin user to the image credentials."
  [handler]
  (fn [session target plan-fn]
    {:pre [(node/node? target)]}
    (let [user (into {} (filter val (node/image-user target)))
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
      (with-action-options session {:user user}
        (handler session target plan-fn)))))

;;; # Phase Execution Functions
(defn execute-one-shot-flag
  "Return a middleware, that will execute a phase on targets that
  don't have the specified state flag set. On successful completion the targets
  have the state flag set."
  [handler state-flag]
  (fn execute-one-shot-flag [session target plan-fn]
    {:pre [(node/node? target)]}
    (when-not (tag/has-state-flag? target state-flag)
      (let [result (handler session target plan-fn)]
        (when-not (seq (plan-errors result))
          (tag/set-state-flag target state-flag))
        result))))

(defn execute-on-filtered
  "Return a function, that will execute a phase on targets that
  return true when applied to the filter-f function."
  [handler filter-f]
  (logging/tracef "execute-on-filtered")
  (fn execute-on-filtered
    [session target plan-fn]
    {:pre [(node/node? target)]}
    (when (filter-f target)
      (handler session target plan-fn))))

(defn execute-on-flagged
  "Return an action middleware, that will execute a phase on nodes
  that have the specified state flag set."
  [handler state-flag]
  (logging/tracef "execute-on-flagged state-flag %s" state-flag)
  (execute-on-filtered handler #(tag/has-state-flag? % state-flag)))

(defn execute-on-unflagged
  "Return an action middleware, that will execute a phase on nodes
  that have the specified state flag set."
  [handler state-flag]
  (logging/tracef "execute-on-flagged state-flag %s" state-flag)
  (execute-on-filtered
   handler (complement #(tag/has-state-flag? % state-flag))))
