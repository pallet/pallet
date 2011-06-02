(ns pallet.action.service
  "Service control."
  (:use clojure.contrib.logging)
  (:require
   [pallet.action :as action]
   [pallet.action.remote-file :as remote-file]
   [pallet.script :as script]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [clojure.string :as string]))

(action/def-bash-action service
  "Control services.

   - :action  accepts either startstop, restart, enable or disable keywords.
   - :if-flag  makes start, stop, and restart confitional on the specified flag
               as set, for example, by remote-file :flag-on-changed
   - :sequence-start  a sequence of [sequence-number level level ...], where
                      sequence number determines the order in which services
                      are started within a level."
  [session service-name & {:keys [action if-flag]
                           :or {action :start}
                           :as options}]
  (if (#{:enable :disable :start-stop} action)
    (stevedore/checked-script
     (format "Confgure service %s" service-name)
     (configure-service ~service-name ~action ~options))
    (if if-flag
      (stevedore/script
       (if (== "1" (lib/flag? ~if-flag))
         (~(str (stevedore/script (~lib/etc-init)) "/" service-name)
          ~(name action))))
      (stevedore/script
       (~(str (stevedore/script (~lib/etc-init)) "/" service-name)
        ~(name action))))))

(defmacro with-restart
  "Stop the given service, execute the body, and then restart."
  [session service-name & body]
  `(let [service# ~service-name]
     (-> ~session
         (service service# :action :stop)
         ~@body
         (service service# :action :start))))

(defn init-script
  "Install an init script.  Sources as for remote-file."
  [session name & {:keys [action url local-file remote-file link
                          content literal template values md5 md5-url force]
                   :or {action :create}
                   :as options}]
  (apply
   remote-file/remote-file
   session
   (str (stevedore/script (~lib/etc-init)) "/" name)
   :action action :owner "root" :group "root" :mode "0755"
   (apply concat options)))
