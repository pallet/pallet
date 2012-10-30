(ns pallet.actions.direct.settings-test
  (:use
   [pallet.actions :only [assoc-settings remote-file-content]]
   [pallet.algo.fsmop :only [failed?]]
   [pallet.api :only [group-spec lift plan-fn with-admin-user]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.core.user :only [*admin-user*]]
   [pallet.crate :only [get-node-settings target-node]]
   [pallet.test-utils
    :only [clj-action make-localhost-compute test-username]]
   [pallet.utils :only [tmpfile with-temporary]]
   [clojure.test :only [deftest is testing use-fixtures]]))

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
            local (group-spec
                   "local" :phases
                   {:assoc (plan-fn
                             [c (remote-file-content
                                 (.getAbsolutePath tmp-file))]
                             (assoc-settings :myapp {:content @c}))
                    :get (plan-fn
                           [node target-node
                            c (get-node-settings node :myapp)]
                           (fn [session] (reset! a c) [c session]))})
            compute (make-localhost-compute :group-name "local")]
        (testing "assoc-settings across phases"
          (let [result (lift local :compute compute :phase [:assoc :get]
                             :user user)]
            @result
            (is (not (failed? result)))
            (is (= {:content "test"} @a))))))))
