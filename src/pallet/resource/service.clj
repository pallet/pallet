(ns pallet.resource.service
  "Service control."
  (:use clojure.contrib.logging)
  (:require
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource :as resource]))


(script/defscript configure-service
  [name action options])

(def debian-configure-option-names
     {:force :f})

(defn debian-options [options]
  (zipmap
   (map #(% debian-configure-option-names %) (keys options))
   (vals options)))

(stevedore/defimpl configure-service :default [name action options]
  ~(condp = action
       :disable (stevedore/script
                 (update-rc.d
                  ~(stevedore/map-to-arg-string
                    (select-keys [:f :n] (debian-options options)))
                  ~name remove))
       :enable (stevedore/script
                (update-rc.d
                 ~(stevedore/map-to-arg-string
                   (select-keys [:n] (debian-options options)))
                 ~name defaults
                 ~(:sequence-start options 20)
                 ~(:sequence-stop options (:sequence-start options 20))))
       :start-stop (stevedore/script ;; start/stop
                    (update-rc.d
                     ~(stevedore/map-to-arg-string
                       (select-keys [:n] (debian-options options)))
                     ~name
                     start ~(:sequence-start options 20)
                     "."
                     stop ~(:sequence-stop options (:sequence-start options 20))
                     "."))))


(defn service*
  [service-name & options]
  (let [opts (apply hash-map options)
        opts (merge {:action :start} opts)
        action (opts :action)]
    (if (#{:enable :disable :start-stop} action)
      (stevedore/checked-script
       (format "Confgure service %s" service-name)
       (configure-service ~service-name ~action ~opts))
      (stevedore/script ( ~(str "/etc/init.d/" service-name) ~(name action))))))

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

