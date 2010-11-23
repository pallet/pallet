(ns pallet.resource.format-test
  (:use clojure.test)
  (:use pallet.resource.format))

(deftest sectioned-properties-test
  (is (= "[a]\nb = 1\n\n[b]\nc = some-path\n\n"
         (sectioned-properties {:a {:b 1}
                                "b" {"c" "some-path"}}))))
