(ns pallet.core.api-builder-test
  (:require
   [clojure.test :refer :all]
   [pallet.core.api-builder :refer [format-sigs]]
   [schema.core :as schema]))

(deftest format-sigs-test
  (is (= "\n\n    Keyword -> Keyword"
         (binding [*ns* 'pallet.core.api-builder-test]
           (format-sigs '[[schema/Keyword :- Keyword]]))))
  (is (= "\n\n    Keyword -> Keyword"
         (format-sigs '[[schema.core/Keyword :- Keyword]]))))
