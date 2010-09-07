(ns pallet.utils-test
  (:use pallet.utils)
  (:use clojure.test
        clojure.contrib.logging
        pallet.test-utils))

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
            :sudo-password password
            :no-sudo nil}
          (into {} (make-user username
                     :password password
                     :private-key-path private-key-path
                     :public-key-path public-key-path
                     :passphrase passphrase))))
    (is (= {:username username
            :password nil
            :private-key-path (default-private-key-path)
            :public-key-path (default-public-key-path)
            :passphrase nil
            :sudo-password nil
            :no-sudo nil}
           (into {} (make-user username))))
    (is (= {:username username
            :password nil
            :private-key-path (default-private-key-path)
            :public-key-path (default-public-key-path)
            :passphrase nil
            :sudo-password password
            :no-sudo nil}
           (into {} (make-user username :sudo-password password))))
    (is (= {:username username
            :password nil
            :private-key-path (default-private-key-path)
            :public-key-path (default-public-key-path)
            :passphrase nil
            :sudo-password nil
            :no-sudo true}
           (into {} (make-user username :no-sudo true))))))

(deftest blank?-test
  (is (blank? nil))
  (is (blank? ""))
  (is (not (blank? "a")))
  (is (not (blank? 'a))))
