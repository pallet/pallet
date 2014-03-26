(ns pallet.core.script-state-test
  (:require
   [clojure.test :refer :all]
   [com.palletops.log-config.timbre :refer [logging-threshold-fixture]]
   [pallet.core.nodes :refer [localhost]]
   [pallet.core.script-state :refer :all]))

(use-fixtures :once (logging-threshold-fixture))

(deftest parse-flags-test
  (is (= {:a true :b true}
         (parse-flags "SETFLAG: a :SETFLAG xyz SETFLAG: b :SETFLAG"))))

(deftest parse-flag-values-test
  (is (= {:a "1" :b "0"}
         (parse-flag-values
          "SETVALUE: a 1 :SETVALUE xyz SETVALUE: b 0 :SETVALUE"))))

(deftest parse-shell-result-test
  (let [out (str "SETVALUE: a 1 :SETVALUE xyz SETVALUE: b 0 :SETVALUE"
                 "SETFLAG: changed :SETFLAG")
        id "mid"]
    (is (= {id  {:changed true :a "1" :b "0"}}
           (update-node-state {} id out)))))
