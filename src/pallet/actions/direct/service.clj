(ns pallet.actions.direct.service
  "Service control. Deprecated in favour of pallet.crate.service."
  (:require
   [pallet.action :refer [implement-action]]
   [pallet.actions :refer [service]]
   [pallet.actions.decl :refer [checked-script]]
   [pallet.actions-impl :refer [init-script-path]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.utils :refer [apply-map]]))

(defmulti service-impl
  (fn [service-name & {:keys [action if-flag if-stopped
                                      service-impl]
                               :or {action :start service-impl :initd}
                               :as options}]
    service-impl))

(defmethod service-impl :initd
  [service-name & {:keys [action if-flag if-stopped
                          service-impl]
                   :or {action :start}
                   :as options}]
  (if (#{:enable :disable :start-stop} action)
    (stevedore/checked-script
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

(defmethod service-impl :upstart
  [service-name & {:keys [action if-flag if-stopped
                          service-impl]
                   :or {action :start}
                   :as options}]
  (if (#{:enable :disable :start-stop} action)
    (checked-script
     (format "Configure service %s" service-name)
     (~lib/configure-service ~service-name ~action ~options))
    (if if-flag
      (stevedore/script
       (println ~(name action) ~service-name "if config changed")
       (if (== "1" (~lib/flag? ~if-flag))
         ("service" ~service-name ~(name action))))
      (if if-stopped
        (stevedore/script
         (println ~(name action) ~service-name "if stopped")
         (if-not ("service" ~service-name status)
           ("service" ~service-name ~(name action))))
        (stevedore/script
         (println ~(name action) ~service-name)
         ("service" ~service-name ~(name action)))))))


(implement-action service :direct
  {:action-type :script :location :target}
  [service-name & {:as options}]
  [{:language :bash}
   (apply-map service-impl service-name options)])
