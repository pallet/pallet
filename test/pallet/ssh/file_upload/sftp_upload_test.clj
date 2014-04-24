(ns pallet.ssh.file-upload.sftp-upload-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [pallet.core.user :refer [*admin-user*]]
   [pallet.script.lib :refer [file user-home]]
   [pallet.ssh.execute :refer [user->credentials]]
   [pallet.ssh.file-upload.sftp-upload :refer :all]
   [pallet.stevedore :refer [fragment]]
   [pallet.test-utils :refer [test-username
                              with-bash-script-language
                              with-ubuntu-script-template]]
   [pallet.transport :as transport]
   [pallet.utils :refer [with-temp-file tmpdir tmpfile]]))

(use-fixtures :once
  with-bash-script-language
  with-ubuntu-script-template)


(deftest upload-dir-test
  (is (= "x/user" (upload-dir "x" "user")))
  (is (= (fragment (user-home "user"))
         (upload-dir ":home" "user")))
  (is (= (fragment (file (user-home "user") "a" "b"))
         (upload-dir ":home/a/b" "user"))))

(deftest target-test
  (is (= "/tmp/user/Bjl2fz6eqtcptUA3p-Kr9Q"
         (target "/tmp" "user" "/a")))
  (is (= (fragment (file (user-home "user") "Bjl2fz6eqtcptUA3p-Kr9Q"))
         (target ":home" "user" "/a")))
  (is (= (fragment (file (user-home "user") "d" "Bjl2fz6eqtcptUA3p-Kr9Q"))
         (target ":home/d" "user" "/a"))))

(def ssh-connection (transport/factory :ssh {}))



(deftest sftp-upload-file-test
  (let [endpoint {:server "127.0.0.1"}
        auth (user->credentials (assoc *admin-user* :username (test-username)))
        content "test"
        target [{:endpoint endpoint :credentials auth}]
        connection (transport/open ssh-connection target {:max-tries 3})]
    (try
      (with-temp-file [local-f content]
        (let [target-f (tmpfile)]
          (.delete target-f)
          (sftp-upload-file connection (str local-f) (str target-f))
          (is (= content (slurp target-f)))
          (.delete target-f)))
      (finally
        (transport/release ssh-connection connection)))))

(deftest sftp-ensure-dir-test
  (let [endpoint {:server "127.0.0.1"}
        auth (user->credentials (assoc *admin-user* :username (test-username)))
        content "test"
        target [{:endpoint endpoint :credentials auth}]
        connection (transport/open ssh-connection target {:max-tries 3})]
    (try
      (let [d (tmpdir)
            f (io/file d "user" "a")]
        (sftp-ensure-dir connection (str f))
        (.isDirectory (.getParentFile f))
        (.delete (.getParentFile f)))
      (finally
        (transport/release ssh-connection connection)))))
