(ns pallet.resource.service
  "Service control."
  (:use clojure.contrib.logging)
  (:require
   [pallet.script :as script]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore.script :as script-impl]
   [pallet.resource.filesystem-layout :as filesystem-layout]
   [pallet.resource.lib :as lib]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource :as resource]
   [clojure.string :as string]))

(script/defscript configure-service
  [name action options])

(def debian-configure-option-names
     {:force :f})

(defn debian-options [options]
  (zipmap
   (map #(% debian-configure-option-names %) (keys options))
   (vals options)))

(script-impl/defimpl configure-service :default [name action options]
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

(def ^{:private true} chkconfig-default-options
  [20 2 3 4 5])

(defn- chkconfig-levels
  [options]
  (->> options (drop 1 ) (map str) string/join))

(script-impl/defimpl configure-service [#{:yum}] [name action options]
  ~(condp = action
       :disable (stevedore/script ("/sbin/chkconfig" ~name off))
       :enable (stevedore/script
                ("/sbin/chkconfig"
                 ~name on
                 "--level" ~(chkconfig-levels
                             (:sequence-start
                              options chkconfig-default-options))))
       :start-stop (stevedore/script ;; start/stop
                    ("/sbin/chkconfig"
                     ~name on
                     "--level" ~(chkconfig-levels
                                 (:sequence-start
                                  options chkconfig-default-options))))))


(resource/defresource service
  "Control services.

   - :action  accepts either startstop, restart, enable or disable keywords.
   - :if-flag  makes start, stop, and restart confitional on the specified flag
               as set, for example, by remote-file :flag-on-changed
   - :sequence-start  a sequence of [sequence-number level level ...], where
                      sequence number determines the order in which services
                      are started within a level."
  (service*
   [request service-name & {:keys [action if-flag]
                            :or {action :start}
                            :as options}]
   (if (#{:enable :disable :start-stop} action)
     (stevedore/checked-script
      (format "Confgure service %s" service-name)
      (configure-service ~service-name ~action ~options))
     (if if-flag
       (stevedore/script
        (if (== "1" (lib/flag? ~if-flag))
          (~(str "/etc/init.d/" service-name) ~(name action))))
       (stevedore/script
        ( ~(str "/etc/init.d/" service-name) ~(name action)))))))

(defmacro with-restart
  "Stop the given service, execute the body, and then restart."
  [request service-name & body]
  `(let [service# ~service-name]
     (-> ~request
         (service service# :action :stop)
         ~@body
         (service service# :action :start))))

(defn init-script
  "Install an init script.  Sources as for remote-file."
  [request name & {:keys [action url local-file remote-file link
                          content literal template values md5 md5-url force]
                   :or {action :create}
                   :as options}]
  (apply
   remote-file/remote-file
   request
   (str (stevedore/script (filesystem-layout/etc-init)) "/" name)
   :action action :owner "root" :group "root" :mode "0755"
   (apply concat options)))
