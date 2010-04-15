(ns pallet.utils-test
  (:use [pallet.utils] :reload-all)
  (:use clojure.test
        pallet.test-utils))

(deftest system-test
  (is (= {:exit 0 :out "" :err ""} (system "/usr/bin/true"))))

(deftest bash-test
  (is (= {:exit 0 :out "fred\n" :err ""} (bash "echo fred"))))

(deftest make-user-test
  (let [username "userfred"
        password "pw"
        private-key-path "pri"
        public-key-path "pub"]
    (is (= {:username username
            :password password
            :private-key-path private-key-path
            :public-key-path public-key-path
            :sudo-password password}
           (make-user username
            :password password
            :private-key-path private-key-path
            :public-key-path public-key-path)))
    (is (= {:username username
            :password nil
            :private-key-path (default-private-key-path)
            :public-key-path (default-public-key-path)
            :sudo-password nil}
           (make-user username)))
        (is (= {:username username
            :password nil
            :private-key-path (default-private-key-path)
            :public-key-path (default-public-key-path)
            :sudo-password password}
           (make-user username :sudo-password password)))))

(deftest sudo-cmd-for-test
  (let [no-pw "/usr/bin/sudo"
        pw "echo \"fred\" | /usr/bin/sudo -S"]
    (is (= no-pw (sudo-cmd-for (make-user "fred"))))
    (is (= pw (sudo-cmd-for (make-user "fred" :password "fred"))))
    (is (= pw (sudo-cmd-for (make-user "fred" :sudo-password "fred"))))
    (is (= no-pw
           (sudo-cmd-for (make-user "fred" :password "fred" :sudo-password false))))))

(deftest sh-script-test
  (let [res (sh-script
             "file=$(mktemp utilXXXX); echo fred > $file ;cat $file ; rm $file")]
    (is (= {:exit 0 :err "" :out "fred\n"} res))))

(deftest cmd-join-test
  (is (= "fred\n" (cmd-join ["fred"])))
  (is (= "fred\nblogs\n" (cmd-join ["fred" "blogs"])))
  (is (= "fred\nblogs\n" (cmd-join ["fred\n\n" "blogs\n"]))))
