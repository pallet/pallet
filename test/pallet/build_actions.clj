(ns pallet.build-actions
  "Test utilities for building actions"
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.action-options :refer [action-options-key]]
   [pallet.compute :as compute]
   [pallet.context :as context :refer [with-phase-context]]
   [pallet.core.node-os :refer [with-script-for-node]]
   [pallet.core.executor.echo :refer [echo-executor]]
   [pallet.core.plan-state.in-memory :refer [in-memory-plan-state]]
   [pallet.environment :as environment]
   [pallet.group :refer [group-spec]]
   [pallet.plan :refer [plan-fn]]
   [pallet.script :as script]
   [pallet.session :refer [plan-state target validate-target-session]]
   [pallet.session.action-plan :refer [target-path]]
   [pallet.session.verify :refer [add-session-verification-key check-session]]
   [pallet.target-ops :refer [execute-target-phase]]
   [pallet.test-utils :as test-utils :refer [remove-source-line-comments]]
   [pallet.user :refer [*admin-user*]]
   [pallet.utils :as utils]))

(defn- trim-if-string [s]
  (when s (string/trim s)))

(defn produce-phases
  "Join the result of execute-action-plan, executing local actions.
   Useful for testing."
  [session f]
  (let [phase (:phase session)
        session (dissoc session :phase)
        _ (validate-target-session session)
        target (target session)]
    (logging/debugf "produce-phases %s" session)
    (assert phase)
    (with-script-for-node target (plan-state session)
      (let [;; [action-plan plan-state]
            ;; ((action-plan
            ;;   (:service-state session) (:environment session) f nil session)
            ;;  (:plan-state session))
            session (dissoc session :target)
            target (assoc-in target [:phases phase] f)
            {:keys [action-results] :as result-map}
            (execute-target-phase session phase target)]
        (logging/debugf "build-actions result-map %s" result-map)
        [(str
          (string/join "\n" (map (comp trim-if-string :script) action-results))
          \newline)
         result-map]))))


;; (fn test-exec-setttings-fn [_ _]
;;              {:user (:user session *admin-user*)
;;               :executor echo-executor
;;               :execute-status-fn stop-execution-on-error})
;;            (assoc (:server session) :phases {phase f})

(defn build-session
  "Takes the session map, and tries to add the most keys possible.
   The default session is
       {:target {:override {:packager :aptitude :os-family :ubuntu}}
        :phase :configure}"
  [session]
  (let [session (or session {})
        session (update-in session [:target]
                           #(or
                             %
                             (group-spec
                                 (or ;; (when-let [node (-> session :target :node)]
                                  ;; (node/group-name node))
                                  :id))))
        session (update-in session [:target :override :os-family]
                           #(or % :ubuntu))
        session (update-in
                 session [:target :node]
                 #(or
                   %
                   (test-utils/make-node
                    (or (-> session :target :group-name) "testnode")
                    {:os-family (or (-> session :target :override :os-family)
                                    :ubuntu)
                     :os-version (or (-> session :target :override :os-version)
                                     "10.04")
                     :packager (or (-> session :target :override :packager)
                                   (compute/packager-for-os
                                    (or (-> session :target :override :os-family)
                                        :ubuntu)
                                    nil))
                     ;; :id (or (-> session :target :node) :id)
                     ;; :is-64bit (get-in session [:is-64bit] true)
                     })))
        ;; session (update-in session [:server] merge (:group session))
        ;; session (update-in session [:service-state] #(or % [(:target session)]))
        session (update-in session [:execution-state :action-options]
                           #(merge {:script-comments nil} %))
        session (update-in session [:execution-state :executor]
                           #(or (echo-executor) %))
        session (update-in session [:phase] #(or % :test-phase))
        session (update-in session [:plan-state]
                           #(or % (in-memory-plan-state)))
        session (update-in session [:execution-state :user]
                           #(or % *admin-user*))]
    (assoc session :type :pallet.session/session)))

(defn build-actions*
  "Implementation for build-actions."
  [f session]
  (let [session (build-session session)
        f (if-let [phase-context (:phase-context session)]
            (fn []
              (with-phase-context {:msg phase-context}
                (f)))
            f)
        [script session] (produce-phases session f)]
    [script session]))

(defmacro build-actions
  "Outputs the remote actions specified in the body for the specified phases.
   This is useful in testing.

   `session` should be a map (but was historically a vector of keyword
   pairs).  See `build-session`."
  [[session-sym session] & body]
  `(let [session# ~session]
     (assert (or (nil? session#) (map? session#)))
     (build-actions* (plan-fn [~session-sym] ~@body) session#)))

(defmacro build-script
  "Outputs the remote actions specified in the body for the specified phases.
   This is useful in testing.

   `session` should be a map (but was historically a vector of keyword
   pairs).  See `build-session`."
  [[session-sym session] & body]
  `(let [session# ~session]
     (assert (or (nil? session#) (map? session#)))
     (first (build-actions* (plan-fn [~session-sym] ~@body) session#))))

(defmacro let-actions
  "Outputs the remote actions specified in the body for the specified phases.
   This is useful in testing.

   `session` should be a map (but was historically a vector of keyword
   pairs).  See `build-session`."
  {:indent 1}
  [[session-sym session] & body]
  `(let [session# ~session]
     (assert (or (nil? session#) (map? session#)))
     (build-actions* (plan-fn [~session-sym] ~@body) session#)))

(def ubuntu-session
  (build-session {:target {:override {:os-family :ubuntu}}}))
(def centos-session
  (build-session {:target {:override {:os-family :centos}}}))

(defn action-phase-errors
  [result]
  (filter :error (:result result)))

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (build-actions 1)(build-script 1))
;; eval: (define-clojure-indent (let-actions 1))
;; End:
