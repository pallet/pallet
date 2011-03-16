(ns pallet.build-actions
  "Test utilities for building actions"
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.compute :as compute]
   [pallet.core :as core]
   [pallet.script :as script]
   [pallet.phase :as phase]
   [clojure.string :as string]))

(defn produce-phases
  "Join the result of excute-action-plan, executing local resources.
   Useful for testing."
  [request]
  (clojure.contrib.logging/info
   (format "produce-phases %s" request ))
  (let [execute
        (fn [request]
          (let [[result request] (->
                                  request
                                  action-plan/build-for-target
                                  action-plan/translate-for-target
                                  (action-plan/execute-for-target
                                   {:script/bash (fn [cmds] cmds)
                                    :fn/clojure (constantly nil)
                                    :transfer/from-local (fn [r & _])
                                    :transfer/to-local (fn [r & _])}))]
            [(string/join "" result) request]))]
    (reduce
     (fn [[results request] phase]
       (let [[result request] (execute (assoc request :phase phase))]
         [(str results result) request]))
     ["" request]
     (#'core/phase-list-with-implicit-phases [(:phase request)]))))

(defmacro build-actions
  "Outputs the remote resources specified in the body for the specified phases.
   This is useful in testing."
  [[& {:as request-map}] & body]
  `(let [f# (phase/phase-fn ~@body)
         request# (or ~request-map {})
         request# (update-in request# [:phase] #(or % :configure))
         request# (assoc-in request# [:phases (:phase request#)] f#)
         request# (update-in request# [:node-type :image :os-family]
                             #(or % :ubuntu))
         request# (update-in request# [:node-type :tag]
                             #(or % :id))
         request# (update-in request# [:target-id]
                             #(or %
                                  (and (:target-node request#)
                                       (keyword
                                        (compute/id (:target-node request#))))
                                  :id))
         request# (update-in request# [:all-nodes]
                             #(or % [(:target-node request#)]))
         request# (update-in request# [:target-nodes]
                             #(or % (:all-nodes request#)))
         request# (update-in
                   request# [:target-packager]
                   #(or
                     %
                     (get-in request# [:node-type :image :packager])
                     (let [os-family# (get-in
                                       request#
                                       [:node-type :image :os-family])]
                       (cond
                        (#{:ubuntu :debian :jeos :fedora} os-family#) :aptitude
                        (#{:centos :rhel} os-family#) :yum
                        (#{:arch} os-family#) :pacman
                        (#{:suse} os-family#) :zypper
                        (#{:gentoo} os-family#) :portage))))]
     (produce-phases request#)))
