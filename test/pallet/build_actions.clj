(ns pallet.build-actions
  "Test utilities for building actions"
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.compute :as compute]
   [pallet.core :as core]
   [pallet.core :as core]
   [pallet.execute :as execute]
   [pallet.phase :as phase]
   [pallet.script :as script]
   [pallet.utils :as utils]
   [clojure.contrib.logging :as logging]
   [clojure.string :as string]))

(defn- apply-phase-to-node
  "Apply a phase to a node session"
  [session]
  {:pre [(:phase session)]}
  ((#'core/middleware-handler #'core/execute) session))

(defn produce-phases
  "Join the result of execute-action-plan, executing local actions.
   Useful for testing."
  [session]
  (let [execute
        (fn [session]
          (let [[result session] (apply-phase-to-node
                                  session)]
            [(string/join "" result) session]))]
    (reduce
     (fn [[results session] phase]
       (let [[result session] (execute (assoc session :phase phase))]
         [(str results result) session]))
     ["" (->
          session
          (assoc :middleware
            [core/translate-action-plan
             execute/execute-echo]
            :executor core/default-executors)
          action-plan/build-for-target)]
     (phase/all-phases-for-phase (:phase session)))))

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
        session (update-in session [:server :group-name]
                           #(or % :id))
        session (update-in session [:server :node-id]
                           #(or %
                                (if-let [node (-> session :server :node)]
                                  (keyword (compute/id node))
                                  :id)))
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
                      (#{:gentoo} os-family) :portage))))]
    session))

(defn build-actions*
  "Implementation for build-actions."
  [f session-arg]
  (let [session (if (map? session-arg)  ; historical compatibility
                  session-arg
                  (convert-0-4-5-compatible-keys (apply hash-map session-arg)))
        session (build-session session)
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
  `(do
     (let [session# ~session]
       (when-not (map? session#)
         (logging/warn
          "Use of vector for session in build-actions is deprecated."))
       (build-actions* (phase/phase-fn [] ~@body) session#))))
