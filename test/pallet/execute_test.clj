(ns pallet.execute-test
  (:use
   clojure.test
   pallet.execute
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.node :only [id]]
   [pallet.test-utils :only [test-session]]))

(use-fixtures :once (logging-threshold-fixture))

(deftest parse-flags-test
  (is (= #{:a :b} (parse-flags "SETFLAG: a :SETFLAG xyz SETFLAG: b :SETFLAG"))))

(deftest parse-flag-values-test
  (is (= {:a "1" :b "0"}
         (parse-flag-values
          "SETVALUE: a 1 :SETVALUE xyz SETVALUE: b 0 :SETVALUE"))))

(deftest parse-shell-result-test
  (let [result {:out (str
                      "SETVALUE: a 1 :SETVALUE xyz SETVALUE: b 0 :SETVALUE"
                      "SETFLAG: changed :SETFLAG")}
        session (test-session)]
    (is (= [(merge {:flags #{:changed}
                    :flag-values {:a "1" :b "0"}} result)
            (merge session
                   {:plan-state
                    {:host
                     {(name (id (-> session :server :node)))
                      {:flags {nil #{:changed}}
                       :flag-values {nil {:a "1" :b "0"}}}}}})]
             (parse-shell-result
              session
              result)))))
