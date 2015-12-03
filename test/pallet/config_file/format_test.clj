(ns pallet.config-file.format-test
  (:require
   [clojure.test :refer :all]
   [pallet.config-file.format :refer :all]))

(deftest sectioned-properties-test
  (is (= "[a]\nb = 1\n\n[b]\nc = some-path\n\n"
         (sectioned-properties
          (array-map :a {:b 1} "b" {"c" "some-path"}))))
  (is (= "b = 1\n[b]\nc = some-path\n\n"
         (sectioned-properties
          (array-map "" {:b 1} "b" {"c" "some-path"})))))

(deftest name-values-test
  (is (= "a 1\nb some-path\n"
         (name-values (array-map :a 1 "b" "some-path"))))
  (is (= "a=1\nb=some-path\n"
         (name-values (array-map :a 1 "b" "some-path") :separator "="))))
