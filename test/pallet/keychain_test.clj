(ns pallet.keychain-test
  (:use pallet.keychain :reload-all)
  (:use clojure.test))

(deftest keychain-passphrase-test
  (is (nil? (keychain-passphrase "" "/some/path"))))
