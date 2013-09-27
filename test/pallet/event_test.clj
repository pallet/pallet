(ns pallet.event-test
  (:require
   [clojure.core.async :refer [chan sliding-buffer timeout alts!!]]
   [clojure.test :refer :all]
   [pallet.event :refer :all]))

(deftest async-publisher-test
  (let [channel (chan (sliding-buffer 1))
        m {:log-level :trace :msg "hello"}]
    (try
      (add-publisher :test-async (async-publisher channel))
      (publish m)
      (is (= m (first (alts!! [channel (timeout 1000)]))))
      (finally
        (remove-publisher :test-async)))))
