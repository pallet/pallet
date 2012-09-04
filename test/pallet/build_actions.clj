(ns pallet.build-actions
  "Test utilities for building actions"
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.context :as context]
   [pallet.compute :as compute]
   [pallet.environment :as environment]
   [pallet.execute :as execute]
   [pallet.node :as node]
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
   [pallet.core.user :only [*admin-user*]]
   [pallet.context :only [with-phase-context]]
   [pallet.executors :only [echo-executor]]
   [pallet.session.action-plan :only [target-path]]
   [pallet.session.verify :only [check-session add-session-verification-key]]
   [pallet.monad :only [let-s wrap-pipeline]]))

(defn- trim-if-string [s]
  (when s (string/trim s)))

(defn produce-phases
  "Join the result of execute-action-plan, executing local actions.
   Useful for testing."
  [session f]
  (binding [action-plan/*defining-context* (context/phase-contexts)]
    (with-script-for-node (-> session :server :node)
      (let [phase (:phase session)
            _ (assert phase)
            [action-plan plan-state]
            ((action-plan
              (:service-state session) (:environment session) f session)
             (:plan-state session))

            {:keys [result] :as result-map}
            (execute-action-plan
             (:service-state session)
             plan-state
             (:environment (:environment session))
             (:user session *admin-user*)
             echo-executor
             stop-execution-on-error
             {:action-plan action-plan
              :phase (:phase session)
              :target (:server session)})]
        [(str
          (string/join "\n" (map (comp trim-if-string second) result))
          \newline)
         result-map]))))

(defn build-session
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
        session (update-in session [:group]
                           #(or
                             %
                             (group-spec
                              (or (when-let [node (-> session :server :node)]
                                    (node/group-name node))
                                  :id))))
        session (update-in
                 session [:server :node]
                 #(or
                   %
                   (test-utils/make-node
                    (-> session :server :group-name)
                    :os-family (or (-> session :server :image :os-family)
                                   :ubuntu)
                    :os-version (or (-> session :server :image :os-version)
                                    "10.04")
                    :packager (or (-> session :group :packager)
                                  (compute/packager
                                   {:os-family
                                    (or (-> session :server :image :os-family)
                                        :ubuntu)}))
                    :id (or (-> session :server :node-id) :id)
                    :is-64bit (get-in session [:is-64bit] true))))
        session (update-in session [:server] merge (:group session))
        session (update-in session [:service-state] #(or % [(:server session)]))
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
            f)]
    (produce-phases session f)))

(defmacro build-actions
  "Outputs the remote actions specified in the body for the specified phases.
   This is useful in testing.

   `session` should be a map (but was historically a vector of keyword
   pairs).  See `build-session`."
  [session & body]
  `(let [session# ~session]
     {:pre [(or (nil? session#) (map? session#))]}
     (build-actions* (plan-fn ~@body) session#)))

(defmacro let-actions
  "Outputs the remote actions specified in the body for the specified phases.
   This is useful in testing.

   `session` should be a map (but was historically a vector of keyword
   pairs).  See `build-session`."
  {:indent 1}
  [session & body]
  `(let [session# ~session]
     (assert (or (nil? session#) (map? session#)))
     (build-actions* (let-s ~@body) session#)))

(def ubuntu-session
  (build-session {:server {:image {:os-family :ubuntu}}}))
(def centos-session
  (build-session {:server {:image {:os-family :centos}}}))
