(ns pallet.actions.direct.settings-test
  (:require
   [clojure.stacktrace :refer [print-cause-trace]]
   [clojure.test :refer :all]
   [pallet.actions :refer [assoc-in-settings assoc-settings remote-file-content]]
   [pallet.algo.fsmop :refer [failed?]]
   [pallet.api :refer [group-spec lift plan-fn with-admin-user]]
   [pallet.argument :refer [delayed]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.user :refer [*admin-user*]]
   [pallet.crate :refer [get-node-settings target-node]]
   [pallet.test-utils :refer [make-localhost-compute test-username]]
   [pallet.utils :refer [tmpfile with-temporary]]))

(require 'pallet.executors)

(use-fixtures :once (logging-threshold-fixture))

(defn- local-test-user
  []
  (assoc *admin-user* :username (test-username) :no-sudo true))

(deftest assoc-settings-test
  (with-admin-user (local-test-user)
    (with-temporary [tmp-file (tmpfile)]
      (spit tmp-file "test")
      (let [user (local-test-user)
            a (atom nil)
            b (atom nil)
            local (group-spec
                   "local" :phases
                   {:assoc (plan-fn
                             (let [c (remote-file-content
                                      (.getAbsolutePath tmp-file))]
                               (assoc-settings
                                :myapp (delayed [_] {:content @c}))
                               (assoc-in-settings [:myapp2 :c] c)))
                    :get (plan-fn
                           (let [node (target-node)
                                 c (get-node-settings node :myapp)
                                 d (get-node-settings node :myapp2)]
                             (reset! a c)
                             (reset! b (:c d))))})
            compute (make-localhost-compute :group-name "local")]
        (testing "assoc-settings across phases"
          (let [result (lift local :compute compute :phase [:assoc :get]
                             :user user :async true)]
            @result
            (is (not (failed? result)))
            (when (failed? result)
              (when-let [e (:exception @result)]
                (print-cause-trace e)))
            (is (= {:content "test"} @a))
            (is (= "test" @b))))))))
