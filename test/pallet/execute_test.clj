(ns pallet.execute-test
  (:use pallet.execute)
  (:use clojure.test
        pallet.test-utils
        clojure.tools.logging)
  (:require
   [clj-ssh.ssh :as ssh]
   [pallet.action-plan :as action-plan]
   [pallet.common.logging.logutils :as logutils]
   [pallet.compute.jvm :as jvm]
   [pallet.compute :as compute]
   [pallet.core :as core]
   [pallet.execute :as execute]
   [pallet.test-utils :as test-utils]
   [pallet.utils :as utils]
   [pallet.script :as script]))

(use-fixtures :once (logutils/logging-threshold-fixture))

(deftest parse-flags-test
  (is (= #{:a :b} (parse-flags "SETFLAG: a :SETFLAG xyz SETFLAG: b :SETFLAG"))))

(deftest parse-flag-values-test
  (is (= {:a "1" :b "0"}
         (parse-flag-values
          "SETVALUE: a 1 :SETVALUE xyz SETVALUE: b 0 :SETVALUE"))))

(deftest parse-shell-result-test
  (let [result {:out (str
                      "SETVALUE: a 1 :SETVALUE xyz SETVALUE: b 0 :SETVALUE"
                      "SETFLAG: changed :SETFLAG")}]
    (is (= [(merge {:flags #{:changed} :flag-values {:a "1" :b "0"}} result)
            {:server {:node-id :n}
             :parameters {:host {:n {:flags #{:changed}
                                     :flag-values {:a "1" :b "0"}}}}}]
           (parse-shell-result
            {:server {:node-id :n}}
            result)))))
