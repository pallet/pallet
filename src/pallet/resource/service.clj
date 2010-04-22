(ns pallet.resource.service
  "Service control."
  (:use pallet.script
        pallet.stevedore
        clojure.contrib.logging)
  (:require
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource :as resource]))

(defn service*
  [service-name & options]
  (let [opts (apply hash-map options)
        opts (merge {:action :start} opts)
        action (opts :action)]
    (script ( ~(str "/etc/init.d/" service-name) ~(name action)))))

(resource/defresource service
  "Control serives"
  service* [service-name & options])

(defmacro with-restart
  "Stop the given service, execute the body, and then restart."
  [service-name & body]
  `(let [service# ~service-name]
     (service service# :action :stop)
     ~@body
     (service service# :action :start)))

(defn init-script*
  "Install an init script.  Sources as for remote-file."
  [name & options]
  (let [filename (str "/etc/init.d/" name)]
    (apply remote-file/remote-file*
           filename :owner "root" :group "root" :mode "0755"
           options)))

(resource/defresource init-script
  "Install an init script.  Sources as for remote-file."
  init-script* [name & options])
