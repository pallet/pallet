(ns pallet.crate.nohup-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions :refer [remote-file]]
   [pallet.build-actions :as build-actions]
   [pallet.crate.nohup :as nohup]
   [pallet.crate.service :refer [service-supervisor-config]]
   [pallet.crate.service-test :refer [service-supervisor-test]]
   [pallet.plan :refer [plan-fn]]
   [pallet.spec :as spec]
   [pallet.stevedore :refer [fragment]]
   [pallet.test-utils :refer :all]))

(defn nohup-test [config]
  (service-supervisor-test :nohup config {:process-name "sleep 100"}))

(def nohup-test-spec
  (let [config {:service-name "myjob"
                :process-name "sleep"
                :run-file {:content (str "#!/bin/sh\nexec /tmp/myjob")}}]
    (spec/server-spec
     {:extends [(nohup/server-spec {})]
      :phases {:settings (plan-fn [session]
                           (service-supervisor-config session :nohup config {}))
               :configure (plan-fn [session]
                            (remote-file
                             session
                             "/tmp/myjob"
                             :content (fragment ("exec" "sleep" 100000000))
                             :mode "0755"))
               :test (plan-fn [session]
                       (nohup-test session config))}})))
