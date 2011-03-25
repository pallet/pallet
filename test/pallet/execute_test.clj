(ns pallet.execute-test
  (:use pallet.execute)
  (:use clojure.test
        pallet.test-utils
        clojure.contrib.logging)
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.compute.jvm :as jvm]
   [pallet.core :as core]
   [pallet.test-utils :as test-utils]
   [pallet.utils :as utils]
   [pallet.script :as script]))

(use-fixtures :once (console-logging-threshold))

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
  (is (= {:exit 0 :out "fred\n" :err ""}
         (bash "echo fred"))))

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
             (str "file=$(mktemp -t utilXXXX);echo fred > \"$file\";"
                  "cat \"$file\";rm \"$file\""))]
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

(deftest execute-with-ssh-test
  (let [user (assoc utils/*admin-user* :username (test-username) :no-sudo true)]
    (binding [utils/*admin-user* user]
      (possibly-add-identity
       (default-agent) (:private-key-path user) (:passphrase user))
      (let [session {:phase :configure
                     :server {:node-id :localhost
                              :node (test-utils/make-localhost-node)}
                     :action-plan
                     {:configure
                      {:localhost (action-plan/add-action
                                   nil
                                   (action-plan/action-map
                                    (fn [session] "ls /") []
                                    :in-sequence :script/bash :target))}}
                     :executor core/default-executors
                     :middleware [core/translate-action-plan
                                  ssh-user-credentials
                                  execute-with-ssh]
                     :user user}
            result (#'core/apply-phase-to-node session)]
        (is (= 2 (count result)))
        (is (= 1 (count (first result))))
        (is (= 0 (:exit (ffirst result))))))))

(deftest local-script-test
  (is (zero? (:exit (local-script "ls")))))

(deftest local-checked-script-test
  (is (zero? (:exit (local-checked-script "ls should work" "ls")))))

(deftest echo-transfer-test
  (is (= [[["a" "b"]] {}]
           (echo-transfer
            {}
            (fn [session] {:value [["a" "b"]] :session session})))))
