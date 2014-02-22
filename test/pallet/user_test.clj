(ns pallet.user-test
  (:require
   [clojure.test :refer :all]
   [pallet.user :refer :all]))

(deftest make-user-test
  (let [username "userfred"
        password "pw"
        private-key-path "pri"
        public-key-path "pub"
        passphrase "key-passphrase"]
    (is (= {:username username
            :password password
            :private-key-path private-key-path
            :public-key-path public-key-path
            :passphrase passphrase
            :sudo-password password}
           (into {}
                 (make-user
                  username
                  {:password password
                   :private-key-path private-key-path
                   :public-key-path public-key-path
                   :passphrase passphrase
                   :sudo-password password}))))))
