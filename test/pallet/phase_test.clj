(ns pallet.phase-test
  (:refer-clojure :exclude [sync])
  (:require
   [clojure.core.async :refer [>! >!! <!! chan go put!]]
   [clojure.stacktrace :refer [root-cause]]
   [clojure.test :refer :all]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.actions :refer [exec-script*]]
   [pallet.compute.protocols :as impl]
   [pallet.compute.test-provider :refer [test-service]]
   [pallet.core.executor.plan :refer [plan-executor]]
   [pallet.core.nodes :refer [localhost]]
   [pallet.core.recorder :refer [results]]
   [pallet.core.recorder.in-memory :refer [in-memory-recorder]]
   [pallet.exception :refer [domain-info]]
   [pallet.node :as node]
   [pallet.plan :refer [errors plan-fn]]
   [pallet.session :as session
    :refer [executor recorder set-target set-user target user]]
   [pallet.spec :refer [server-spec]]
   [pallet.phase :refer :all]
   [pallet.user :as user]
   [pallet.utils.async :refer [go-try sync]]
   [schema.core :as schema :refer [validate]]
   [taoensso.timbre :refer [debugf]]))

(use-fixtures :once (logging-threshold-fixture))

(defn plan-session
  "Return a session with a plan executor."
  []
  (-> (session/create {:executor (plan-executor)
                       :recorder (in-memory-recorder)})
      (set-user user/*admin-user*)))

(deftest parallel-phases-test
  (let [c (chan)]
    (testing "one result without exceptions"
      (let [fs [(fn [_ c] (go (>! c {:results [true]})))]]
        (parallel-phases fs nil c)
        (let [r (<!! c)]
          (is (= {:results [true]} r))
          (is (nil? (when-let [e (:exception r)] (throw e)))))))
    (testing "one result with exceptions"
      (let [e (ex-info "some exception" {})
            fs [(fn [_ c] (go (>! c {:results [true] :exception e})))]]
        (parallel-phases fs nil c)
        (let [{:keys [results exception]} (<!! c)]
          (is (= [true] results))
          (is (= e (root-cause exception))))))
    (testing "two result without exceptions"
      (let [fs [(fn [_ c] (go (>! c {:results [:ok1]})))
                (fn [_ c] (go (>! c {:results [:ok2]})))]]
        (parallel-phases fs nil c)
        (let [r (<!! c)]
          (is (= {:results [:ok1 :ok2]} r))
          (is (nil? (when-let [e (:exception r)] (throw e)))))))
    (testing "two result with exception"
      (let [e (ex-info "some exception" {})
            fs [(fn [_ c] (go (>! c {:results [:ok1] :exception e})))
                (fn [_ c] (go (>! c {:results [:ok2]})))]]
        (parallel-phases fs nil c)
        (let [{:keys [results exception]} (<!! c)]
          (is (= [:ok1 :ok2] results))
          (is (= e (root-cause exception))))))))
