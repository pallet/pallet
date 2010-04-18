(ns pallet.utils-test
  (:use [pallet.utils] :reload-all)
  (:use clojure.test
        clojure.contrib.logging
        pallet.test-utils))

(deftest system-test
  (cond
   (.canRead (java.io.File. "/usr/bin/true")) (is (= {:exit 0 :out "" :err ""}
                                                     (system "/usr/bin/true")))
   (.canRead (java.io.File. "/bin/true")) (is (= {:exit 0 :out "" :err ""}
                                                 (system "/bin/true")))
   :else (warn "Skipping system-test")))

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
            :sudo-password password
            :no-sudo nil}
           (make-user username
                      :password password
                      :private-key-path private-key-path
                      :public-key-path public-key-path)))
    (is (= {:username username
            :password nil
            :private-key-path (default-private-key-path)
            :public-key-path (default-public-key-path)
            :sudo-password nil
            :no-sudo nil}
           (make-user username)))
    (is (= {:username username
            :password nil
            :private-key-path (default-private-key-path)
            :public-key-path (default-public-key-path)
            :sudo-password password
            :no-sudo nil}
           (make-user username :sudo-password password)))
    (is (= {:username username
            :password nil
            :private-key-path (default-private-key-path)
            :public-key-path (default-public-key-path)
            :sudo-password nil
            :no-sudo true}
           (make-user username :no-sudo true)))))

(deftest sudo-cmd-for-test
  (let [no-pw "/usr/bin/sudo -n"
        pw "echo \"fred\" | /usr/bin/sudo -S -n"
        no-sudo ""]
    (is (= no-pw (sudo-cmd-for (make-user "fred"))))
    (is (= pw (sudo-cmd-for (make-user "fred" :password "fred"))))
    (is (= pw (sudo-cmd-for (make-user "fred" :sudo-password "fred"))))
    (is (= no-pw
           (sudo-cmd-for (make-user "fred" :password "fred" :sudo-password false))))
    (is (= no-sudo (sudo-cmd-for (make-user "root"))))
    (is (= no-sudo (sudo-cmd-for (make-user "fred" :no-sudo true))))))

(deftest sh-script-test
  (let [res (sh-script
             "file=$(mktemp utilXXXX); echo fred > $file ;cat $file ; rm $file")]
    (is (= {:exit 0 :err "" :out "fred\n"} res))))

(deftest cmd-join-test
  (is (= "fred\n" (cmd-join ["fred"])))
  (is (= "fred\nblogs\n" (cmd-join ["fred" "blogs"])))
  (is (= "fred\nblogs\n" (cmd-join ["fred\n\n" "blogs\n"]))))
