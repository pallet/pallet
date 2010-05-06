(ns pallet.md5crypt-test
  (:use pallet.md5crypt :reload-all)
  (:use clojure.test))

(deftest salt-test
  (is (= 8 (count (salt))))
  (let [x (salt)]
    (is (not (every? #(= % (first x)) x)))))

(deftest to64-test
  (is (= "v/.." (to64 123 4)))
  (is (= "mz2j" (to64 12341234 4)))
  (is (= "GH" (to64 1234 2))))

(deftest clean-salt-test
  (is (= "abcdefgh" (clean-salt "abcdefgh" md5-magic)))
  (is (= "abcdefgh" (clean-salt "abcdefghi" md5-magic)))
  (is (= "abcdefgh" (clean-salt "$1$abcdefghi" md5-magic)))
  (is (= "abcdefg" (clean-salt "abcdefg$h" md5-magic))))

(deftest byte-as-unsigned-test
  (is (= 255 (byte-as-unsigned -1)))
  (is (= 127 (byte-as-unsigned 127)))
  (is (= 128 (byte-as-unsigned -128))))

(deftest crypt-test
  (is (= "$1$abcdefgh$bk5sRhBFZOxBLBo682wdn/"
         (pallet.md5crypt/crypt "abcdefgh" "abcdefgh")))
  (is (= "$1$tpmKs23u$tnhcI5KDzNKmAUM8ogYjv."
         (pallet.md5crypt/crypt "h1wcB9luZaRpazS" "tpmKs23u" "$1$")))
  (is (= "$1$tpmKs23u$gH/bJKFhWcfL6Bx8b7SD1."
         (pallet.md5crypt/crypt "hcBupzS" "tpmKs23u"))))
