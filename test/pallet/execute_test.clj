(ns pallet.execute-test
  (:use pallet.execute)
  (:use clojure.test
        pallet.test-utils
        clojure.contrib.logging)
  (:require
   [pallet.compute.jvm :as jvm]
   [pallet.utils :as utils]
   [pallet.script :as script]))

(use-fixtures
 :each
 (fn bind-default-agent [f]
   (binding [default-agent-atom (atom nil)]
     (f))))

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
  (script/with-template [:ubuntu]
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
  (script/with-template [:centos-5.3]
    (let [no-pw "/usr/bin/sudo"
          pw "echo \"fred\" | /usr/bin/sudo -S"
          no-sudo ""]
      (is (= no-pw (sudo-cmd-for (utils/make-user "fred"))))
      (is (= pw (sudo-cmd-for (utils/make-user "fred" :password "fred"))))
      (is (= pw (sudo-cmd-for (utils/make-user "fred" :sudo-password "fred"))))
      (is (= no-pw
             (sudo-cmd-for
              (utils/make-user "fred" :password "fred" :sudo-password false))))
      (is (= no-sudo (sudo-cmd-for (utils/make-user "root"))))
      (is (= no-sudo (sudo-cmd-for (utils/make-user "fred" :no-sudo true)))))))

(deftest sh-script-test
  (let [res (sh-script
             "file=$(mktemp -t utilXXXX);echo fred > \"$file\";cat \"$file\";rm \"$file\"")]
    (is (= {:exit 0 :err "" :out "fred\n"} res))))


(deftest remote-sudo-test
  (let [user (assoc utils/*admin-user* :username (test-username))]
    (binding [utils/*admin-user* user]
      (possibly-add-identity
       (default-agent) (:private-key-path user) (:passphrase user))
      (script/with-template [(jvm/os-family)]
        (let [result (remote-sudo
                      "localhost"
                      "ls"
                      (assoc user :no-sudo true))]
          (is (zero? (:exit result))))))))

(deftest execute-ssh-cmds-test
  (let [user (assoc utils/*admin-user* :username (test-username))]
    (binding [utils/*admin-user* user]
      (possibly-add-identity
       (default-agent) (:private-key-path user) (:passphrase user))
      (let [result
            (script/with-template [:ubuntu]
              (execute-ssh-cmds
               "localhost"
               {:target-id :id
                :phase :configure
                :action-plan
                {:configure
                 {:id [{:location :remote
                        :f (fn [request] "ls /")
                        :type :script/bash}]}}}
               (assoc user :no-sudo true)
               {}))]
        (is (= 2 (count result)))
        (is (= 1 (count (first result))))
        (is (= 0 (:exit (ffirst result))))))))

(deftest local-script-test
  (local-script "ls"))

(deftest local-checked-script-test
  (local-checked-script "ls should work" "ls"))
