(ns pallet.cache-test
  (:require
   [pallet.cache :as cache]
   [pallet.cache.impl :as impl])
  (:use
   clojure.test))

(deftest fifo-test
  (let [fifo (cache/make-fifo-cache :limit 3)]
    (is (not (impl/lookup fifo :a)))
    (is (not (get fifo :a)))
    (is (= 0 (count fifo)))
    (is (= ::miss (impl/lookup fifo :a ::miss)))
    (is (= ::miss (get fifo :a ::miss)))
    (is (not (impl/has? fifo :a)))
    (is (not (contains? fifo :a)))
    (testing "Adding an entry"
      (cache/miss fifo :a 1)
      (is (= 1 (impl/lookup fifo :a)))
      (is (= 1 (get fifo :a)))
      (is (impl/has? fifo :a))
      (is (contains? fifo :a))
      (is (= 1 (count fifo))))
    (testing "Filling the cache"
      (cache/miss fifo :b 2)
      (cache/miss fifo :c 3)
      (is (= 1 (impl/lookup fifo :a)))
      (is (= 2 (impl/lookup fifo :b)))
      (is (= 3 (impl/lookup fifo :c)))
      (is (impl/has? fifo :a))
      (is (impl/has? fifo :b))
      (is (impl/has? fifo :c))
      (is (= 3 (count fifo))))
    (testing "Expiration"
      (cache/miss fifo :d 4)
      (is (nil? (impl/lookup fifo :a)))
      (is (= ::miss (impl/lookup fifo :a ::miss)))
      (is (= 2 (impl/lookup fifo :b)))
      (is (= 3 (impl/lookup fifo :c)))
      (is (= 4 (impl/lookup fifo :d)))
      (is (not (impl/has? fifo :a)))
      (is (impl/has? fifo :b))
      (is (impl/has? fifo :c))
      (is (impl/has? fifo :d))
      (is (= 3 (count fifo))))
    (testing "Explicit Expiration"
      (cache/expire fifo :d)
      (is (nil? (impl/lookup fifo :d)))
      (is (= ::miss (impl/lookup fifo :d ::miss)))
      (is (= 2 (impl/lookup fifo :b)))
      (is (= 3 (impl/lookup fifo :c)))
      (is (not (impl/has? fifo :a)))
      (is (impl/has? fifo :b))
      (is (impl/has? fifo :c))
      (is (not (impl/has? fifo :d)))
      (is (= 2 (count fifo))))
    (testing "Expiration of everything"
      (cache/expire-all fifo)
      (is (not (impl/has? fifo :a)))
      (is (not (impl/has? fifo :b)))
      (is (not (impl/has? fifo :c)))
      (is (not (impl/has? fifo :d)))
      (is (= 0 (count fifo))))))
