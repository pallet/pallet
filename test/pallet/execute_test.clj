(ns pallet.execute-test
  (:use pallet.execute)
  (:use clojure.test
        clojure.contrib.logging)
  (:require
   [pallet.utils :as utils]))

(deftest system-test
  (cond
   (.canRead (java.io.File. "/usr/bin/true")) (is (= {:exit 0 :out "" :err ""}
                                                     (system "/usr/bin/true")))
   (.canRead (java.io.File. "/bin/true")) (is (= {:exit 0 :out "" :err ""}
                                                 (system "/bin/true")))
   :else (warn "Skipping system-test")))

(deftest bash-test
  (is (= {:exit 0 :out "fred\n" :err ""} (bash "echo fred"))))

(deftest sudo-cmd-for-test
  (let [no-pw "/usr/bin/sudo -n"
        pw "echo \"fred\" | /usr/bin/sudo -S"
        no-sudo ""]
    (is (= no-pw (sudo-cmd-for (utils/make-user "fred"))))
    (is (= pw (sudo-cmd-for (utils/make-user "fred" :password "fred"))))
    (is (= pw (sudo-cmd-for (utils/make-user "fred" :sudo-password "fred"))))
    (is (= no-pw
           (sudo-cmd-for
            (utils/make-user "fred" :password "fred" :sudo-password false))))
    (is (= no-sudo (sudo-cmd-for (utils/make-user "root"))))
    (is (= no-sudo (sudo-cmd-for (utils/make-user "fred" :no-sudo true))))))

(deftest sh-script-test
  (let [res (sh-script
             "file=$(mktemp utilXXXX);echo fred > $file ;cat $file ;rm $file")]
    (is (= {:exit 0 :err "" :out "fred\n"} res))))


(deftest execute-ssh-cmds-test
  (let [user utils/*admin-user*]
    (possibly-add-identity
     (default-agent) (:private-key-path user) (:passphrase user))
    (let [result (execute-ssh-cmds
                  "localhost"
                  {:commands [{:location :remote :f (fn [request] "ls /")
                               :type :script/bash}]}
                  (assoc user :no-sudo true)
                  {})]
      (is (= 2 (count result)))
      (is (= 1 (count (first result))))
      (is (= 0 (:exit (ffirst result)))))))
