(ns pallet.utils.async-test
  (:require
   [clojure.core.async :refer [<!! chan to-chan]]
   [clojure.test :refer :all]
   [pallet.utils.async :refer :all]
   [simple-check.core :as sc]
   [simple-check.generators :as gen]
   [simple-check.properties :as prop]))

;; (deftest timeout-chan-test
;;   (let [c (chan)
;;         t (timeout-chan c 100)]
;;     (is (nil? (first (<!! t))))))

(def gen-non-empty-vector-of-non-nils
  (gen/not-empty (gen/vector (gen/such-that (complement nil?) gen/any))))

(def to-chan-from-chan-inverse
  (prop/for-all [v gen-non-empty-vector-of-non-nils]
    (= (seq v) (seq (from-chan (to-chan v))))))

(deftest from-chan-test
  (testing "from-chan is the inverse of to-chan"
    (let [result (sc/quick-check 20 to-chan-from-chan-inverse)]
      (is (nil? (:fail result)))
      (is (:result result)))))

(def concat-chans-verify
  ;; restrict to keywords so we can sort reliably
  (prop/for-all [v (gen/vector (gen/vector gen/keyword))]
    (try
      (let [c (chan)]
        (concat-chans (map to-chan v) c)
        (let [r (sort (from-chan c))
              t (sort (apply concat v))]
          (= t r)))
      (catch Exception e
        (clojure.stacktrace/print-cause-trace e)))))

(deftest concat-chans-test
  (testing "concat-chans concats"
    (let [result (sc/quick-check 20 concat-chans-verify)]
      (is (nil? (:fail result)))
      (is (:result result)))))
