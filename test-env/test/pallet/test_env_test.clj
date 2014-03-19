(ns pallet.test-env-test
  (:require
   [clojure.test :refer :all]
   [pallet.compute :refer [compute-service?]]
   [pallet.test-env :refer [test-env *node-spec-meta* *compute-service*]]))

(def nsm {:node-spec {:image {:image-id ""}}
          :name "fred"
          :selectors #{:x}})

(test-env
 {:node-list {:variants [nsm]}}
 {:pallet/test-env
  {:service {:provider :node-list}
   :test-specs [{:selector :x
                 :expected [{:feature ["env-test" "throws-expected"]
                             :expected? (fn [result]
                                          (= 1 (:x (ex-data
                                                    (:actual result)))))}
                            {:feature ["env-test" "fred"]
                             :expected? (fn [result]
                                          (= "oops" (:message result)))}
                            {:feature ["env-test" "not-supported"]
                             :expected? :not-supported}]}]}})

(deftest env-test
  (is *node-spec-meta*)
  (is (compute-service? *compute-service*))
  (testing (:name *node-spec-meta*)
    (is (= "fred" (:name *node-spec-meta*)) "something")
    (is (= 1 0) "oops"))
  (testing "throws expected"
    (is (= 1 (throw (ex-info "something" {:x 1})))))
  (testing "not-supported"
    (is (= 1 (throw (ex-info "not supported" {:not-supported true}))))))
