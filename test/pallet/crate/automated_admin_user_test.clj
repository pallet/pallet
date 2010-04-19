(ns pallet.crate.automated-admin-user-test
  (:use [pallet.crate.automated-admin-user] :reload-all)
  (:require pallet.resource
            pallet.utils
            pallet.crate.sudoers)
  (:use clojure.test
        pallet.test-utils))

(with-private-vars [pallet.crate.authorize-key
                    []])

(deftest automated-admin-user-test
  (is (= (str "if getent passwd fred; then usermod --shell /bin/bash fred;else useradd --shell /bin/bash --create-home fred;fi\nmkdir -p $(getent passwd fred | cut -d: -f6)/.ssh/\nchown  fred $(getent passwd fred | cut -d: -f6)/.ssh/\nchmod  755 $(getent passwd fred | cut -d: -f6)/.ssh/\nfile=$(getent passwd fred | cut -d: -f6)/.ssh/authorized_keys\ncat > ${file} <<EOF\n"
              (slurp (pallet.utils/default-public-key-path))
              "\nEOF\nchmod 0644 ${file}\nchown fred ${file}\nfile=/etc/sudoers\ncat > ${file} <<EOF\nroot ALL = (ALL) ALL\n%wheel ALL = (ALL) ALL\nfred ALL = (ALL) NOPASSWD: ALL\nEOF\nchmod 0440 ${file}\nchown root ${file}\n")
         (pallet.resource/build-resources [] (automated-admin-user "fred")))))
