(ns pallet.resource-build
  "Temporary namespace - needs to move to testing, but need to resource-when"
  (:require
   [pallet.resource :as resource]
   [pallet.compute :as compute]
   [pallet.script :as script]
   [clojure.string :as string]))

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

(defmacro build-resources
  "Outputs the remote resources specified in the body for the specified phases.
   This is useful in testing."
  [[& {:as request-map}] & body]
  `(let [f# (resource/phase ~@body)
         request# (or ~request-map {})
         request# (update-in request# [:phase]
                             #(or % :configure))
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
     (script/with-template
       [(-> request# :node-type :image :os-family)
        (-> request# :target-packager)]
       (produce-phases [(:phase request#)] (f# request#)))))
