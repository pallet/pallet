(ns pallet.main-test
  (:use pallet.main)
  (:use
   clojure.test
   pallet.test-utils))

(deftest parse-as-qualified-symbol-test
  (is (nil? (parse-as-qualified-symbol "a")))
  (is (nil? (parse-as-qualified-symbol "a.b")))
  (are ['a.b 'a.b.c] (= (parse-as-qualified-symbol "a.b/c"))))

(deftest map-and-resolve-symbols-test
  (is (= {'pallet.main-test-ns/x 1}
         (reduce map-and-resolve-symbols {}
                 ["a" "b" "1" "pallet.main-test-ns/x"])))
  (is (= 1 (var-get (find-var 'pallet.main-test-ns/x)))))
