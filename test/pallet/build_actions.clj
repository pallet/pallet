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
  "Apply a phase to a node request"
  [request]
  {:pre [(:phase request)]}
  ((#'core/middleware-handler #'core/execute) request))

(defn produce-phases
  "Join the result of execute-action-plan, executing local actions.
   Useful for testing."
  [request]
  (let [execute
        (fn [request]
          (let [[result request] (apply-phase-to-node
                                  (->
                                   request
                                   (assoc :middleware
                                     [core/translate-action-plan
                                      execute/execute-echo]
                                     :executor core/default-executors)
                                   action-plan/build-for-target))]
            [(string/join "" result) request]))]
    (reduce
     (fn [[results request] phase]
       (let [[result request] (execute (assoc request :phase phase))]
         [(str results result) request]))
     ["" request]
     (phase/phase-list-with-implicit-phases [(:phase request)]))))

(defn- convert-0-4-5-compatible-keys
  "Convert old build-actions keys to new keys."
  [request]
  (let [request (if-let [node-type (:node-type request)]
                  (do
                    (logging/info ":node-type -> :server")
                    (-> request
                        (assoc :server node-type)
                        (dissoc :node-type)))
                  request)
        request (if-let [target-node (:target-node request)]
                  (do
                    (logging/info ":target-node -> [:server :node]")
                    (-> request
                        (assoc-in [:server :node] target-node)
                        (assoc-in [:server :node-id]
                                  (keyword (compute/id target-node)))
                        (dissoc :target-node)))
                  request)
        request (if-let [target-id (:target-id request)]
                  (do
                    (logging/info ":target-id -> [:server :node-id]")
                    (-> request
                        (assoc-in [:server :node-id] target-id)
                        (dissoc :target-id)))
                  request)
        request (if-let [packager (-> request :server :image :packager)]
                  (do
                    (logging/info
                     "[:node-type :image :package] -> [:server :packager]")
                    (-> request
                        (assoc-in [:server :packager] packager)
                        (update-in [:server :image] dissoc :packager)))
                  request)
        request (if-let [target-packager (:target-packager request)]
                  (do
                    (logging/info ":target-packager -> [:server :packager]")
                    (-> request
                        (assoc-in [:server :packager] target-packager)
                        (dissoc :target-packager)))
                  request)]
    request))

(defn- build-request
  "Takes the request map, and tries to add the most keys possible.
   The default request is
       {:all-nodes [nil]
        :server {:packager :aptitude
                     :node-id :id
                     :tag :id
                     :image {:os-family :ubuntu}}
        :phase :configure}"
  [request-map]
  (let [request (or request-map {})
        request (update-in request [:phase]
                           #(or % :configure))
        request (update-in request [:server :image :os-family]
                           #(or % :ubuntu))
        request (update-in request [:server :group-name]
                           #(or % :id))
        request (update-in request [:server :node-id]
                           #(or %
                                (if-let [node (-> request :server :node)]
                                  (keyword (compute/id node))
                                  :id)))
        request (update-in request [:all-nodes]
                           #(or % [(-> request :server :node)]))
        request (update-in
                 request [:server :packager]
                 #(or
                   %
                   (get-in request [:server :packager])
                   (get-in request [:packager])
                   (let [os-family (get-in
                                     request
                                     [:server :image :os-family])]
                     (cond
                      (#{:ubuntu :debian :jeos :fedora} os-family) :aptitude
                      (#{:centos :rhel} os-family) :yum
                      (#{:arch} os-family) :pacman
                      (#{:suse} os-family) :zypper
                      (#{:gentoo} os-family) :portage))))]
    request))

(defn build-actions*
  "Implementation for build-actions."
  [f request-map]
  (let [request (if (map? request-map)  ; historical compatibility
                  request-map
                  (convert-0-4-5-compatible-keys (apply hash-map request-map)))
        request (build-request request)
        request (assoc-in request [:server :phases (:phase request)] f)
        request (if (map? request-map)  ; historical compatibility
                  request
                  ((#'core/add-request-keys-for-0-4-5-compatibility
                    identity)
                   request))]
    (produce-phases request)))

(defmacro build-actions
  "Outputs the remote actions specified in the body for the specified phases.
   This is useful in testing.

   `request-map` should be a map (but was historically a vector of keyword
   pairs).  See `build-request`."
  [request-map & body]
  `(do
     (let [request-map# ~request-map]
       (when-not (map? request-map#)
         (logging/warn
          "Use of vector for request-map in build-actions is deprecated."))
       (build-actions* (phase/phase-fn ~@body) request-map#))))
