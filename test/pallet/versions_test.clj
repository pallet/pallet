(ns pallet.versions-test
  (:require
   [clojure.test :refer :all]
   [pallet.versions :refer :all]))

(deftest version-vector-test
  (is (= [1] (version-vector "1")))
  (is (= [1 2] (version-vector "1.2")))
  (is (= [1 2 3] (version-vector "1.2.3"))))

(deftest as-version-vector-test
  (is (= [1] (as-version-vector "1")))
  (is (= [1 2] (as-version-vector [1 2])))
  (is (= [1 2 3] (as-version-vector "1.2.3"))))

(deftest as-version-vector?-test
  (is (version-vector? [1]))
  (is (version-vector? [1 2]))
  (is (not (version-vector? "1.2"))))

(deftest as-version-range?-test
  (is (version-range? [[1 2]]))
  (is (version-range? [nil [1 2]]))
  (is (version-range? [[1 2][2 1]]))
  (is (not (version-range? "1.2")))
  (is (not (version-range? ["1.2"])))
  (is (not (version-range? [1 2]))))

(deftest as-version-spec?-test
  (is (version-spec? [1 2]))
  (is (version-spec? [[1 2]]))
  (is (version-spec? [nil [1 2]]))
  (is (version-spec? [[1 2][2 1]]))
  (is (not (version-spec? "1.2")))
  (is (not (version-spec? ["1.2"]))))

(deftest version-less-test
  (is (version-less [1 2] [1 3]))
  (is (version-less [1] [1 3]))
  (is (version-less [1 2 3] [1 3]))
  (is (not (version-less [1 3 2] [1 3])))
  (is (not (version-less [1 2 3] [1 2])))
  (is (not (version-less [1 3] [1 2 3])))
  (is (version-less nil [1 2]))
  (is (not (version-less [1 2] nil))))

(deftest version-matches?-test
  (is (version-matches? [1 2] nil))
  (is (version-matches? [1 2] [1 2]))
  (is (version-matches? [1 2 3] [1 2]))
  (is (version-matches? [1 2 3] [[1 2] [1 3]])))
