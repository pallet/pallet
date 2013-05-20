(ns pallet.ssh.credentials-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [pallet.core.user :refer [make-user]]
   [pallet.ssh.credentials :refer [ensure-ssh-credential
                                   generate-keypair-files
                                   ssh-credential-status]]
   [pallet.utils :refer [tmpfile with-temp-file]]))

(deftest ssh-credential-status-test
  (with-temp-file [pub "pub"]
    (with-temp-file [priv "priv"]
      (let [user (make-user "fred" {:public-key-path (.getPath pub)
                                    :private-key-path (.getPath priv)})]
        (testing "invalid key"
          (is (= :invalid-key (ssh-credential-status user))))
        (generate-keypair-files user {})
        (testing "Valid key"
          (is (= :valid-credential (ssh-credential-status user))))
        (testing "Missing private key"
          (is (= :private-key-not-found
                 (ssh-credential-status
                  (assoc user :private-key-path "invalid-path!@")))))
        (testing "Missing public key"
          (is (= :public-key-not-found
                 (ssh-credential-status
                  (assoc user :public-key-path "invalid-path!@")))))
        (testing "Missing keys"
          (is (= :not-found
                 (ssh-credential-status
                  (assoc user
                    :public-key-path "invalid-path!@"
                    :private-key-path "invalid-path!@")))))))))

(deftest ensure-ssh-credential-test
  (with-temp-file [pub "pub"]
    (with-temp-file [priv "priv"]
      (let [user (make-user "fred" {:public-key-path (.getPath pub)
                                    :private-key-path (.getPath priv)})]
        (testing "invalid key"
          (is (thrown? Exception (ensure-ssh-credential user {}))))
        (generate-keypair-files user {})
        (testing "Valid key"
          (is (nil? (ensure-ssh-credential user {}))))
        (testing "Missing private key"
          (is (thrown? Exception
                       (ensure-ssh-credential
                        (assoc user :private-key-path "invalid-path!@")
                        {}))))
        (testing "Missing public key"
          (is (thrown? Exception
                       (ensure-ssh-credential
                        (assoc user :public-key-path "invalid-path!@")
                        {}))))
        (testing "Password"
          (is (nil?
                 (ensure-ssh-credential
                  (make-user "fred" {:password "somepwd"})
                  {}))))))))
