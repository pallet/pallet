(ns pallet.build-actions
  "Test utilities for building actions"
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.context :as context]
   [pallet.compute :as compute]
   [pallet.core :as core]
   [pallet.core :as core]
   [pallet.environment :as environment]
   [pallet.execute :as execute]
   [pallet.executors :as executors]
   [pallet.phase :as phase]
   [pallet.script :as script]
   [pallet.test-utils :as test-utils]
   [pallet.utils :as utils]
   [clojure.tools.logging :as logging]
   [clojure.string :as string])
  (:use
   [pallet.context :only [with-phase-context]]
   [pallet.session-verify :only [check-session add-session-verification-key]]
   [pallet.monad :only [let-s wrap-pipeline]]))

(defn- apply-phase-to-node
  "Apply a phase to a node session"
  [session]
  {:pre [(:phase session)]}
  (check-session session)
  ((#'core/middleware-handler #'core/execute) session))

(defn produce-phases
  "Join the result of execute-action-plan, executing local actions.
   Useful for testing."
  [session]
  (letfn [(execute-join
            [session]
            (let [[result session] (apply-phase-to-node session)]
              (logging/tracef "result %s session %s" result session)
              [(string/join
                \newline
                (map
                 #(if (= {:language :bash} (first %))
                    (second %) %)
                 result)) session]))]
    (binding [action-plan/*defining-context* (context/phase-contexts)]
      (reduce
       (fn [[results session] phase]
         (logging/tracef "results %s session %s phase %s" results session phase)
         (let [[result session] (execute-join (assoc session :phase phase))]
           (logging/tracef "new result %s session %s" result session)
           [(str results result) session]))
       ["" (->
            session
            (environment/session-with-environment
              (environment/merge-environments
               (:environment session)
               {:algorithms core/default-algorithms
                :executor #'executors/echo-executor
                :middleware [core/translate-action-plan]}))
            action-plan/build-for-target
            second ;; drop the phase result
            ((fn [session]
               (-> session
                   (assoc-in (action-plan/target-path session)
                             (:pallet.action/action-plan session))
                   (dissoc :pallet.action/action-plan)))))]
       (phase/all-phases-for-phase (:phase session))))))

(defn- convert-0-4-5-compatible-keys
  "Convert old build-actions keys to new keys."
  [session]
  (let [session (if-let [node-type (:node-type session)]
                  (do
                    (logging/info ":node-type -> :server")
                    (-> session
                        (assoc :server node-type)
                        (dissoc :node-type)))
                  session)
        session (if-let [target-node (:target-node session)]
                  (do
                    (logging/info ":target-node -> [:server :node]")
                    (-> session
                        (assoc-in [:server :node] target-node)
                        (assoc-in [:server :node-id]
                                  (keyword (compute/id target-node)))
                        (dissoc :target-node)))
                  session)
        session (if-let [target-id (:target-id session)]
                  (do
                    (logging/info ":target-id -> [:server :node-id]")
                    (-> session
                        (assoc-in [:server :node-id] target-id)
                        (dissoc :target-id)))
                  session)
        session (if-let [packager (-> session :server :image :packager)]
                  (do
                    (logging/info
                     "[:node-type :image :package] -> [:server :packager]")
                    (-> session
                        (assoc-in [:server :packager] packager)
                        (update-in [:server :image] dissoc :packager)))
                  session)
        session (if-let [target-packager (:target-packager session)]
                  (do
                    (logging/info ":target-packager -> [:server :packager]")
                    (-> session
                        (assoc-in [:server :packager] target-packager)
                        (dissoc :target-packager)))
                  session)]
    session))

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
        session (update-in session [:phase]
                           #(or % :configure))
        session (update-in session [:server :image :os-family]
                           #(or % :ubuntu))
        session (update-in session [:server :image :os-version]
                           #(or % "11.10"))
        session (update-in session [:server :group-name]
                           #(or % :id))
        session (update-in session [:server :node-id]
                           #(or %
                                (if-let [node (-> session :server :node)]
                                  (keyword (compute/id node))
                                  :id)))
        session (update-in
                 session [:server :node]
                 #(or
                   %
                   (test-utils/make-node
                    (-> session :server :group-name)
                    :os-family (-> session :server :image :os-family)
                    :id (-> session :server :node-id)
                    :is-64bit (-> session :is-64bit))))
        session (update-in session [:all-nodes]
                           #(or % [(-> session :server :node)]))
        session (update-in
                 session [:server :packager]
                 #(or
                   %
                   (get-in session [:server :packager])
                   (get-in session [:packager])
                   (let [os-family (get-in
                                    session
                                    [:server :image :os-family])]
                     (cond
                      (#{:ubuntu :debian :jeos :fedora} os-family) :aptitude
                      (#{:centos :rhel} os-family) :yum
                      (#{:arch} os-family) :pacman
                      (#{:suse} os-family) :zypper
                      (#{:gentoo} os-family) :portage))))
        session (update-in
                 session [:target-id]
                 #(or
                   %
                   (get-in session [:server :node-id])
                   (get-in session [:group :group-name])))
        session (update-in
                 session [:target-type]
                 #(or
                   %
                   (and (get-in session [:server :node-id]) :server)
                   (and (get-in session [:group :group-name]) :group)))]
    (add-session-verification-key session)))

(defn build-actions*
  "Implementation for build-actions."
  [f session-arg]
  (let [session (if (map? session-arg)  ; historical compatibility
                  session-arg
                  (convert-0-4-5-compatible-keys (apply hash-map session-arg)))
        session (build-session session)
        f (if-let [phase-context (:phase-context session)]
            (wrap-pipeline build-action-with-context
              (with-phase-context {:msg phase-context})
              f)
            f)
        session (assoc-in session [:server :phases (:phase session)] f)
        session (if (map? session-arg)  ; historical compatibility
                  session
                  ((#'core/add-session-keys-for-0-4-compatibility
                    identity)
                   session))]
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
     (build-actions* (phase/phase-fn ~@body) session#)))

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
