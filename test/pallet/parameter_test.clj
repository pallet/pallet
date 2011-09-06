(ns pallet.parameter-test
  (:use pallet.parameter)
  (:use clojure.test pallet.test-utils)
  (:require
   pallet.argument
   [pallet.action :as action]
   [pallet.build-actions :as build-actions])
  (:import
   slingshot.Stone))

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
    (is (thrown? Stone (get-for p [:b :c :d])))
    (is (= ::abc (get-for p [:b :c :d] ::abc)))))


(action/def-bash-action lookup-test-action
  [session a]
  (str a))

(deftest lookup-test
  (is (= "9\n"
         (first (build-actions/build-actions
                 {:parameters {:a 1 :b 9}}
                 (lookup-test-action (lookup :b)))))))

(action/def-clj-action parameters-test
  "An action that tests parameter values for equality with the argument
   supplied values."
  [session & {:as options}]
  (let [parameters (:parameters session)]
    (doseq [[[key & keys] value] options]
      (is (= value
             (let [param-value (get parameters key ::not-set)]
               (is (not= ::not-set param-value))
               (if (seq keys)
                 (get-in param-value keys)
                 param-value))))))
  session)

(deftest set-parameters-test
  (let [[res session] (build-actions/build-actions
                       {}
                       (parameters [:a] 33)
                       (parameters [:b] 43)
                       (parameters-test [:a] 33))]
    (is (= {:a 33 :b 43} (:parameters session)))))

(deftest get-target-settings-test
  (let [m {:a 1 :b { :c 2}}
        p {:parameters {:host {:id {:f {:default m}}}}
           :server {:node-id :id}}]
    (is (= m (get-target-settings p :f :default)))
    (is (= m (get-target-settings p :f nil)))))

(deftest assoc-target-settings-test
  (let [m {:a 1 :b { :c 2}}
        p {:server {:node-id :id}}]
    (is (= {:parameters {:host {:id {:f {:default m}}}}
            :server {:node-id :id}}
           (assoc-target-settings p :f :default m)))
    (is (= {:parameters {:host {:id {:f {:default m}}}}
            :server {:node-id :id}}
           (assoc-target-settings p :f nil m)))))
