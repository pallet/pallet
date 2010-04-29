(ns pallet.task.providers-test
  (:use [pallet.task.providers] :reload-all)
  (:require [pallet.core :as core])
  (:use
   clojure.test
   pallet.test-utils))


(with-private-vars [pallet.task.providers
                    [provider-properties enabled?]]

  (deftest provider-properties-test
    (is (map? (provider-properties)))
    (is (every? string? (keys (provider-properties))))
    (is (every? string? (vals (provider-properties)))))

  (deftest enabled?-test
    (is (enabled? "java.lang.String"))
    (is (not (enabled? "java.lang.StringThatDoesntExist")))))
