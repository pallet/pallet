(ns pallet.resource.service
  "Service control."
  (:use pallet.script
        pallet.stevedore
        [pallet.resource :only [defresource]]
        clojure.contrib.logging))

(defn service*
  [service-name & options]
  (let [opts (apply hash-map options)
        opts (merge {:action :start} opts)
        action (opts :action)]
    (script ( ~(str "/etc/init.d/" service-name) ~(name action)))))

(defresource service
  "Control serives"
  service* [service-name & options])

(defmacro with-restart
  "Stop the given service, execute the body, and then restart."
  [service-name & body]
  `(let [service# ~service-name]
     (service service# :action :stop)
     ~@body
     (service service# :action :start)))

