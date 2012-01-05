(ns pallet.action.service
  "Service control."
  (:use
   clojure.tools.logging
   [pallet.monad :only [phase-pipeline]]
   [pallet.utils :only [apply-map]])
  (:require
   [pallet.action :as action]
   [pallet.action-plan :as action-plan]
   [pallet.action.remote-file :as remote-file]
   [pallet.context :as context]
   [pallet.script :as script]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [clojure.string :as string]))

(defn init-script-path
  "Path to the specified init script"
  [service-name]
  (str (stevedore/script (~lib/etc-init)) "/" service-name))

(action/def-bash-action service
  "Control services.

   - :action  accepts either startstop, restart, enable or disable keywords.
   - :if-flag  makes start, stop, and restart confitional on the specified flag
               as set, for example, by remote-file :flag-on-changed
   - :sequence-start  a sequence of [sequence-number level level ...], where
                      sequence number determines the order in which services
                      are started within a level."
  [session service-name & {:keys [action if-flag if-stopped]
                           :or {action :start}
                           :as options}]
  (if (#{:enable :disable :start-stop} action)
    (action-plan/checked-script
     (format "Configure service %s" service-name)
     (~lib/configure-service ~service-name ~action ~options))
    (if if-flag
      (stevedore/script
       (println ~(name action) ~service-name "if config changed")
       (if (== "1" (~lib/flag? ~if-flag))
         (~(init-script-path service-name) ~(name action))))
      (if if-stopped
        (stevedore/script
         (println ~(name action) ~service-name "if stopped")
         (if-not (~(init-script-path service-name) status)
           (~(init-script-path service-name) ~(name action))))
        (stevedore/script
         (println ~(name action) ~service-name)
         (~(init-script-path service-name) ~(name action)))))))

(defmacro with-restart
  "Stop the given service, execute the body, and then restart."
  [service-name & body]
  `(let [service# ~service-name]
     (phase-pipeline with-restart {:service service#}
       (service service# :action :stop)
       ~@body
       (service service# :action :start))))

(defn init-script
  "Install an init script.  Sources as for remote-file."
  [service-name & {:keys [action url local-file remote-file link
                          content literal template values md5 md5-url
                          force]
                   :or {action :create}
                   :as options}]
  (phase-pipeline init-script {}
    (apply-map
     remote-file/remote-file
     (init-script-path service-name)
     :action action :owner "root" :group "root" :mode "0755"
     options)))
