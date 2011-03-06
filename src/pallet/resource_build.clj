(ns pallet.resource-build
  "Temporary namespace - needs to move to testing, but need to resource-when"
  (:require
   [pallet.core :as core]
   [pallet.resource :as resource]
   [pallet.compute :as compute]
   [pallet.script :as script]
   [clojure.string :as string]
   [clojure.contrib.logging :as logging]))

(defn produce-phases
  "Join the result of produce-phase, executing local resources.
   Useful for testing."
  [phases request]
  (clojure.contrib.logging/trace
   (format "produce-phases %s %s" phases request))
  (let [execute
        (fn [request]
          (let [commands (resource/produce-phase request)
                [result request] (if commands
                                   (resource/execute-commands
                                    (assoc request :commands commands)
                                    {:script/bash (fn [cmds] cmds)
                                     :transfer/from-local (fn [& _])
                                     :transfer/to-local (fn [& _])})
                                   [nil request])]
            [(string/join "" result) request]))]
    (reduce
     #(let [[result request] (execute (assoc (second %1) :phase %2))]
        [(str (first %1) result) request])
     ["" request]
     (resource/phase-list phases))))

(defn- convert-0-4-5-compatible-keys
  "Convert old build-resources keys to new keys."
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

(defn build-resources*
  "Implementation for build-resources."
  [f request-map]
  (let [request (if (map? request-map)  ; historical compatibility
                  request-map
                  (convert-0-4-5-compatible-keys (apply hash-map request-map)))
        request (build-request request)
        request (if (map? request-map)  ; historical compatibility
                  request
                  ((#'core/add-request-keys-for-0-4-5-compatibility
                    identity)
                   request))]
    (script/with-template
      [(-> request :server :image :os-family)
       (-> request :server :packager)]
      (produce-phases [(:phase request)] (f request)))))

(defmacro build-resources
  "Outputs the remote resources specified in the body for the specified phases.
   This is useful in testing.

   `request-map` should be a map (but was historically a vector of keyword
   pairs).  See `build-request`."
  [request-map & body]
  `(do
     (let [request-map# ~request-map]
       (when-not (map? request-map#)
         (logging/warn
          "Use of vector for request-map in build-resources is deprecated."))
       (build-resources*
        (resource/phase
         (resource/check-request-map "The request passed to the pipeline")
         ~@(mapcat
            (fn [form] [form `(resource/check-request-map '~form)])
            body))
        request-map#))))
