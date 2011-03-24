(ns pallet.config-file.format-test
  (:use clojure.test)
  (:use pallet.config-file.format))

(deftest sectioned-properties-test
  (is (= "[a]\nb = 1\n\n[b]\nc = some-path\n\n"
         (sectioned-properties {:a {:b 1}
                                "b" {"c" "some-path"}}))))

(deftest name-values-test
  (is (= "a 1\nb some-path\n"
         (name-values {:a 1 "b" "some-path"})))
    (is (= "a=1\nb=some-path\n"
         (name-values {:a 1 "b" "some-path"} :separator "="))))
