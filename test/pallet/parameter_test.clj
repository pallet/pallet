(ns pallet.parameter-test
  (:use pallet.parameter)
  (:use clojure.test pallet.test-utils)
  (:require
   pallet.argument
   [pallet.resource :as resource])
  (:import
   clojure.contrib.condition.Condition))

;; (use-fixtures :each reset-default-parameters)

;; (deftest default-test
;;   (default :a 1 :b 2)
;;   (is (= {:default {:a 1 :b 2}} @*default-parameters*))
;;   (default :c 2)
;;   (is (= {:default {:a 1 :b 2 :c 2}} @*default-parameters*)))

;; (deftest default-for-test
;;   (default-for :os :a 1 :b 2)
;;   (is (= {:os {:a 1 :b 2}} @*default-parameters*)))


(deftest with-parameters-test
  (is (= {:a 1 :b 2}
         (from-map {:default {:a 1 :b 2}} [:default])))
  (is (= {:a 1 :b 3}
         (from-map {:default {:a 1 :b 2} :os {:b 3}} [:default :os]))))

(deftest lookup-test
  (let [l (lookup :b)]
    (is (= 2 (pallet.argument/evaluate l {:parameters {:a 1 :b 2}}))))
  (let [l (lookup :b :c)]
    (is (= 3
           (pallet.argument/evaluate
            l {:parameters {:a 1 :b {:c 3}}})))))

(deftest get-for-test
  (let [p {:parameters {:a 1 :b { :c 2}}}]
    (is (= 1 (get-for p [:a])))
    (is (= 2 (get-for p [:b :c])))
    (is (thrown? Condition (get-for p [:b :c :d])))
    (is (= ::abc (get-for p [:b :c :d] ::abc)))))


(resource/defresource lookup-test-resource
  (lookup-test-resource*
   [request a]
   (str a)))

(deftest lookup-test
  (is (= "9\n"
         (first (build-resources
                 [:parameters {:a 1 :b 9}]
                 (lookup-test-resource (lookup :b)))))))

(resource/deflocal parameters-test
  "A resource that tests parameter values for equality with the argument
   supplied values."
  (parameters-test*
   [request & {:as options}]
   (let [parameters (:parameters request)]
     (doseq [[[key & keys] value] options]
       (is (= value
              (let [param-value (get parameters key ::not-set)]
                (is (not= ::not-set param-value))
                (if (seq keys)
                  (get-in param-value keys)
                  param-value))))))
   request))

(deftest set-parameters-test
  (let [[res request] (build-resources
                       []
                       (parameters [:a] 33)
                       (parameters [:b] 43)
                       (parameters-test [:a] 33))]
    (is (= {:a 33 :b 43} (:parameters request)))))
