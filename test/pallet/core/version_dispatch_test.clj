(ns pallet.core.version-dispatch-test
  (:require
   [clojure.test :refer :all]
   [pallet.core.version-dispatch :refer :all]))

(deftest version-spec-more-specific-test
  (testing "spec vs range"
    (is (version-spec-more-specific [1 2] [[1 0] [1 4]]))
    (is (not (version-spec-more-specific [[1 0] [1 4]] [1 2]))))
  (testing "Nested ranges"
    (is (version-spec-more-specific [[1 2] [1 3]] [[1 0] [1 4]]))
    (is (not (version-spec-more-specific [[1 0] [1 4]] [[1 2] [1 3]]))))
  (testing "Same range start"
    (is (version-spec-more-specific [[1 0] [1 3]] [[1 0] [1 4]]))
    (is (not (version-spec-more-specific [[1 0] [1 4]] [[1 0] [1 3]]))))
  (testing "Same range end"
    (is (version-spec-more-specific [[1 2] [1 4]] [[1 0] [1 4]]))
    (is (not (version-spec-more-specific [[1 0] [1 4]] [[1 2] [1 4]]))))
  (is (version-spec-more-specific [1 3] [[1 2] [1 4]]))
  (is (not (version-spec-more-specific [[1 0] [1 4]] [[1 0] [1 4]])))
  (is (not (version-spec-more-specific [[1 0] [1 4]] [[1 2] [1 3]])))
  (is (version-spec-more-specific [[1 2] [1 3]] [[1 0] [2 0]]))
  (is (version-spec-more-specific [[1 1] [1 4]] [[1 0] [2 0]]))
  (is (not (version-spec-more-specific [[1 0] [2 0]] [[1 2] [1 3]])))
  (is (not (version-spec-more-specific [[1 0] [2 0]] [[1 1] [1 4]]))))
