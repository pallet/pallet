(ns pallet.build-actions
  "Test utilities for building actions"
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.action-options :refer [action-options-key]]
   [pallet.api :refer [group-spec plan-fn]]
   [pallet.compute :as compute]
   [pallet.context :as context]
   [pallet.context :refer [with-phase-context]]
   [pallet.core.api
    :refer [action-plan execute-phase-on-target stop-execution-on-error]]
   [pallet.core.api-impl :refer [with-script-for-node]]
   [pallet.core.user :refer [*admin-user*]]
   [pallet.environment :as environment]
   [pallet.execute :as execute]
   [pallet.executors :refer [echo-executor]]
   [pallet.node :as node]
   [pallet.phase :as phase]
   [pallet.script :as script]
   [pallet.session.action-plan :refer [target-path]]
   [pallet.session.verify :refer [add-session-verification-key check-session]]
   [pallet.test-utils :as test-utils :refer [remove-source-line-comments]]
   [pallet.utils :as utils]))

(defn- trim-if-string [s]
  (when s (string/trim s)))

(defn produce-phases
  "Join the result of execute-action-plan, executing local actions.
   Useful for testing."
  [session f]
  (with-script-for-node (-> session :server) (-> session :plan-state)
    (let [phase (:phase session)
          _ (assert phase)
          ;; [action-plan plan-state]
          ;; ((action-plan
          ;;   (:service-state session) (:environment session) f nil session)
          ;;  (:plan-state session))

          {:keys [plan-state result] :as result-map}
          (execute-phase-on-target
           (:service-state session)
           (:plan-state session)
           (:environment session)
           phase
           (fn test-exec-setttings-fn [_ _]
             {:user (:user session *admin-user*)
              :executor echo-executor
              :execute-status-fn stop-execution-on-error})
           (assoc (:server session) :phases {phase f}))]
      (logging/debugf "build-actions result-map %s" result-map)
      [(str
        (string/join "\n" (map (comp trim-if-string :script) result))
        \newline)
       result-map])))

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
                    (or (-> session :server :group-name) "testnode")
                    :os-family (or (-> session :server :image :os-family)
                                   :ubuntu)
                    :os-version (or (-> session :server :image :os-version)
                                    "10.04")
                    :packager (or (-> session :group :packager)
                                  (compute/packager-for-os
                                   (or (-> session :server :image :os-family)
                                       :ubuntu)
                                   nil))
                    :id (or (-> session :server :node-id) :id)
                    :is-64bit (get-in session [:is-64bit] true))))
        session (update-in session [:server] merge (:group session))
        session (update-in session [:service-state] #(or % [(:server session)]))
        session (update-in session [:plan-state action-options-key]
                           #(merge {:script-comments nil} %))
        session (update-in session [:phase] #(or % :test-phase))
        session (update-in session [:environment :user] #(or % *admin-user*))]
    (add-session-verification-key session)))

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
  [session & body]
  `(let [session# ~session]
     {:pre [(or (nil? session#) (map? session#))]}
     (build-actions* (plan-fn ~@body) session#)))

(defmacro build-script
  "Outputs the remote actions specified in the body for the specified phases.
   This is useful in testing.

   `session` should be a map (but was historically a vector of keyword
   pairs).  See `build-session`."
  [session & body]
  `(let [session# ~session]
     {:pre [(or (nil? session#) (map? session#))]}
     (first (build-actions* (plan-fn ~@body) session#))))

(defmacro let-actions
  "Outputs the remote actions specified in the body for the specified phases.
   This is useful in testing.

   `session` should be a map (but was historically a vector of keyword
   pairs).  See `build-session`."
  {:indent 1}
  [session & body]
  `(let [session# ~session]
     (assert (or (nil? session#) (map? session#)))
     (build-actions* (plan-fn ~@body) session#)))

(def ubuntu-session
  (build-session {:server {:image {:os-family :ubuntu}}}))
(def centos-session
  (build-session {:server {:image {:os-family :centos}}}))

(defn action-phase-errors
  [result]
  (filter :error (:result result)))

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (build-actions 1)(build-script 1))
;; eval: (define-clojure-indent (let-actions 1))
;; End:
