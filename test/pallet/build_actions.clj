(ns pallet.build-actions
  "Test utilities for building actions"
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.context :as context]
   [pallet.compute :as compute]
   [pallet.environment :as environment]
   [pallet.execute :as execute]
   [pallet.phase :as phase]
   [pallet.script :as script]
   [pallet.test-utils :as test-utils]
   [pallet.utils :as utils]
   [clojure.tools.logging :as logging]
   [clojure.string :as string])
  (:use
   [pallet.action-plan :only [stop-execution-on-error]]
   [pallet.api :only [group-spec plan-fn]]
   [pallet.core.api :only [action-plan execute-action-plan]]
   [pallet.core.api-impl :only [with-script-for-node]]
   [pallet.context :only [with-phase-context]]
   [pallet.executors :only [echo-executor]]
   [pallet.session.action-plan :only [target-path]]
   [pallet.session.verify :only [check-session add-session-verification-key]]
   [pallet.monad :only [let-s wrap-pipeline]]))

;; (defn- apply-phase-to-node
;;   "Apply a phase to a node session"
;;   [session]
;;   {:pre [(:phase session)]}
;;   (check-session session)
;;   ((#'core/middleware-handler #'core/execute) session))

(defn produce-phases
  "Join the result of execute-action-plan, executing local actions.
   Useful for testing."
  [session]
  (letfn [;; (execute-join
          ;;   [session]
          ;;   (let [[result session] (apply-phase-to-node session)]
          ;;     (logging/tracef "result %s session %s" result session)
          ;;     [(string/join
          ;;       \newline
          ;;       (map
          ;;        #(if (= {:language :bash} (first %))
          ;;           (second %) %)
          ;;        result)) session]))
          ]
    (binding [action-plan/*defining-context* (context/phase-contexts)]
      (with-script-for-node (-> session :server :node)
        (let [phase (:phase session)
              _ (assert phase)
              [action-plan plan-state]
              ((action-plan
                (:service-state session)
                (:environment session)
                (-> session :group :phases phase)
                session)
               (:plan-state session))

              {:keys [result]}
              (execute-action-plan
               (:service-state session)
               (:plan-state session)
               (:user session utils/*admin-user*)
               echo-executor
               stop-execution-on-error
               {:action-plan action-plan
                :phase (:phase session)
                :target-type :node
                :target (-> session :server :node)})]
          result))

      ;; (reduce
      ;;  (fn [[results session] phase]
      ;;    (logging/tracef "results %s session %s phase %s" results session phase)
      ;;    (let [[result session] (execute-join (assoc session :phase phase))]
      ;;      (logging/tracef "new result %s session %s" result session)
      ;;      [(str results result) session]))
      ;;  ["" (->
      ;;       session
      ;;       (environment/session-with-environment
      ;;         (environment/merge-environments
      ;;          (:environment session)
      ;;          {:algorithms core/default-algorithms
      ;;           :executor #'executors/echo-executor
      ;;           :middleware [core/translate-action-plan]}))
      ;;       core/build-for-target
      ;;       second)]                    ; drop the phase result
      ;;  (phase/all-phases-for-phase (:phase session)))
      )))

(defn- build-session
  "Takes the session map, and tries to add the most keys possible.
   The default session is
       {:all-nodes [nil]
        :server {:packager :aptitude
                     :node-id :id
                     :tag :id
                     :image {:os-family :ubuntu}}
        :phase :configure}"
  [session]
  (let [session (or session {})
        session (update-in session [:server :group]
                           #(or % (group-spec :id)))
        session (update-in
                 session [:server :node]
                 #(or
                   %
                   (test-utils/make-node
                    (-> session :server :group-name)
                    :os-family (or (-> session :server :image :os-family)
                                   :ubuntu)
                    :id (or (-> session :server :node-id) :id)
                    :is-64bit (or (-> session :is-64bit) true))))
        session (update-in session [:server-state]
                           #(or % {:node->groups
                                   {(-> session :server :node)
                                    [(-> session :server :group)]}
                                   :group->nodes
                                   {(-> session :server :group)
                                    [(-> session :server :node)]}}))
        session (update-in session [:phase] #(or % :test-phase))]
    (add-session-verification-key session)))

(defn build-actions*
  "Implementation for build-actions."
  [f session]
  (let [session (build-session session)
        f (if-let [phase-context (:phase-context session)]
            (wrap-pipeline build-action-with-context
              (with-phase-context {:msg phase-context})
              f)
            f)
        session (assoc-in session [:group :phases (:phase session)] f)]
    (produce-phases session)))

(defmacro build-actions
  "Outputs the remote actions specified in the body for the specified phases.
   This is useful in testing.

   `session` should be a map (but was historically a vector of keyword
   pairs).  See `build-session`."
  [session & body]
  `(let [session# ~session]
     (when-not (map? session#)
       (logging/warn
        "Use of vector for session in build-actions is deprecated."))
     (build-actions* (plan-fn ~@body) session#)))

(defmacro ^{:indent 1} let-actions
  "Outputs the remote actions specified in the body for the specified phases.
   This is useful in testing.

   `session` should be a map (but was historically a vector of keyword
   pairs).  See `build-session`."
  [session & body]
  `(let [session# ~session]
     (when-not (map? session#)
       (logging/warn
        "Use of vector for session in build-actions is deprecated."))
     (build-actions* (let-s ~@body) session#)))
