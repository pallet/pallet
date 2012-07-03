(ns pallet.actions.direct.service
  "Service control."
  (:use
   clojure.tools.logging
   [pallet.action :only [implement-action]]
   [pallet.actions :only [service]]
   [pallet.actions-impl :only [init-script-path]]
   [pallet.monad :only [phase-pipeline]]
   [pallet.utils :only [apply-map]])
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.actions.direct.remote-file :as remote-file]
   [pallet.context :as context]
   [pallet.script :as script]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [clojure.string :as string]))

(implement-action service :direct
  {:action-type :script :location :target}
  [session service-name & {:keys [action if-flag if-stopped]
                           :or {action :start}
                           :as options}]
  [[{:language :bash}
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
           (~(init-script-path service-name) ~(name action))))))]
   session])
