(ns pallet.utils-test
  (:use pallet.utils)
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

(deftest sudo-cmd-for-test
  (let [no-pw "/usr/bin/sudo -n"
        pw "echo \"fred\" | /usr/bin/sudo -S"
        no-sudo ""]
    (is (= no-pw (sudo-cmd-for (make-user "fred"))))
    (is (= pw (sudo-cmd-for (make-user "fred" :password "fred"))))
    (is (= pw (sudo-cmd-for (make-user "fred" :sudo-password "fred"))))
    (is (= no-pw
           (sudo-cmd-for
            (make-user "fred" :password "fred" :sudo-password false))))
    (is (= no-sudo (sudo-cmd-for (make-user "root"))))
    (is (= no-sudo (sudo-cmd-for (make-user "fred" :no-sudo true))))))

(deftest sh-script-test
  (let [res (sh-script
             "file=$(mktemp utilXXXX);echo fred > $file ;cat $file ;rm $file")]
    (is (= {:exit 0 :err "" :out "fred\n"} res))))

(deftest blank?-test
  (is (blank? nil))
  (is (blank? ""))
  (is (not (blank? "a")))
  (is (not (blank? 'a))))


(deftest remote-sudo-script-test
  (is (= 0
         ((remote-sudo-script
           "localhost"
           "ls /"
           (assoc *admin-user* :no-sudo true))
          :exit)))
  (is (thrown? com.jcraft.jsch.JSchException
         ((remote-sudo-script
           "localhost"
           "ls /"
           (assoc *admin-user* :no-sudo true)
           :port 1)
          :exit)))
  (is (thrown?
       clojure.contrib.condition.Condition
       (remote-sudo-script
        "localhost"
        "exit 1"
        (assoc *admin-user* :no-sudo true)))))

(deftest remote-sudo-cmds-test
  (let [result (remote-sudo-cmds
                "localhost"
                ["ls /"]
                (assoc *admin-user* :no-sudo true)
                {})]
    (is (= 1 (count result)))
    (is (= 0 (:exit (first result))))))
