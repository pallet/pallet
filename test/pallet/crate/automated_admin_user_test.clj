(ns pallet.crate.automated-admin-user-test
  (:use pallet.crate.automated-admin-user)
  (:require pallet.resource
            pallet.utils
            pallet.crate.sudoers)
  (:use clojure.test
        pallet.test-utils))

(use-fixtures :each with-null-target)

(deftest automated-admin-user-test
  (testing "with defaults"
    (is (=
         (pallet.stevedore/do-script
          (pallet.resource.user/user* "fred" :create-home true :shell :bash)
          (str
           "file=/etc/sudoers\ncat > ${file} <<EOF\nroot ALL = (ALL) ALL\n%adm ALL = (ALL) ALL\nfred ALL = (ALL) NOPASSWD: ALL\nEOF\nchmod 0440 ${file}\nchown root ${file}\n")
          (pallet.crate.ssh-key/authorize-key*
           "fred" (slurp (pallet.utils/default-public-key-path))))
         (test-resource-build
          [nil {:image [:ubuntu]}]
          (automated-admin-user "fred")))))

  (testing "with path"
    (is (=
         (pallet.stevedore/do-script
          (pallet.resource.user/user* "fred" :create-home true :shell :bash)
          (str
           "file=/etc/sudoers\ncat > ${file} <<EOF\nroot ALL = (ALL) ALL\n%adm ALL = (ALL) ALL\nfred ALL = (ALL) NOPASSWD: ALL\nEOF\nchmod 0440 ${file}\nchown root ${file}\n")
          (pallet.crate.ssh-key/authorize-key*
           "fred" (slurp (pallet.utils/default-public-key-path))))
         (test-resource-build
          [nil {:image [:ubuntu]}]
          (automated-admin-user "fred" (pallet.utils/default-public-key-path))))))

  (testing "with byte array"
    (is (=
         (pallet.stevedore/do-script
          (pallet.resource.user/user* "fred" :create-home true :shell :bash)
          (str
           "file=/etc/sudoers\ncat > ${file} <<EOF\nroot ALL = (ALL) ALL\n%adm ALL = (ALL) ALL\nfred ALL = (ALL) NOPASSWD: ALL\nEOF\nchmod 0440 ${file}\nchown root ${file}\n")
          (pallet.crate.ssh-key/authorize-key*
           "fred" "abc"))
         (test-resource-build
          [nil {:image [:ubuntu]}]
          (automated-admin-user "fred" (.getBytes "abc")))))))
