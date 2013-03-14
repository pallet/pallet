(ns pallet.crate.nohup-test
  (:use
   clojure.test
   pallet.test-utils)
  (:require
   [pallet.actions :refer [remote-file]]
   [pallet.api :refer [plan-fn] :as api]
   [pallet.build-actions :as build-actions]
   [pallet.crate.service :refer [service-supervisor-config]]
   [pallet.crate.service-test :refer [service-supervisor-test]]
   [pallet.crate.nohup :as nohup]
   [pallet.stevedore :refer [fragment]]))

(defn nohup-test [config]
  (service-supervisor-test :nohup config {:process-name "sleep 100"}))

(def nohup-test-spec
  (let [config {:service-name "myjob"
                :process-name "sleep"
                :run-file {:content (str "#!/bin/sh\nexec /tmp/myjob")}}]
    (api/server-spec
     :extends [(nohup/server-spec {})]
     :phases {:settings (plan-fn (service-supervisor-config :nohup config {}))
              :configure (plan-fn
                           (remote-file
                            "/tmp/myjob"
                            :content (fragment ("exec" "sleep" 100000000))
                            :mode "0755"))
              :test (plan-fn (nohup-test config))})))
