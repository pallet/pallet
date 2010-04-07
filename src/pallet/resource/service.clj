(ns pallet.resource.service
  "Service control."
  (:use pallet.script
        pallet.stevedore
        [pallet.resource :only [defcomponent]]
        clojure.contrib.logging))

(defn service*
  [service-name & options]
  (let [opts (if (seq options) (apply hash-map options) {})
        opts (merge {:action :start} opts)
        action (opts :action)]
    (script ( ~(str "/etc/init.d/" service-name) ~(name action)))))

(defcomponent service
  "Control serives"
  service* [service-name & options])
