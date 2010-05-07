(ns pallet.crate.automated-admin-user-test
  (:use [pallet.crate.automated-admin-user] :reload-all)
  (:require pallet.resource
            pallet.utils
            pallet.crate.sudoers)
  (:use clojure.test
        pallet.test-utils))

(use-fixtures :each with-null-target)

(deftest automated-admin-user-test
  (is (=
       (str
        "if getent passwd fred; then usermod --shell /bin/bash fred;else useradd --shell /bin/bash --create-home fred;fi\necho \"directory $(getent passwd fred | cut -d: -f6)/.ssh/...\"\n{ mkdir -p $(getent passwd fred | cut -d: -f6)/.ssh/ && chown  fred $(getent passwd fred | cut -d: -f6)/.ssh/ && chmod  755 $(getent passwd fred | cut -d: -f6)/.ssh/; } || { echo directory $(getent passwd fred | cut -d: -f6)/.ssh/ failed ; exit 1 ; } >&2 \necho \"...done\"\necho \"remote-file $(getent passwd fred | cut -d: -f6)/.ssh/authorized_keys...\"\n{ { cat > $(getent passwd fred | cut -d: -f6)/.ssh/authorized_keys <<EOF\n"
        (slurp (pallet.utils/default-public-key-path))
        "\nEOF\n } && chown  fred $(getent passwd fred | cut -d: -f6)/.ssh/authorized_keys && chmod  0644 $(getent passwd fred | cut -d: -f6)/.ssh/authorized_keys; } || { echo remote-file $(getent passwd fred | cut -d: -f6)/.ssh/authorized_keys failed ; exit 1 ; } >&2 \necho \"...done\"\nfile=/etc/sudoers\ncat > ${file} <<EOF\nroot ALL = (ALL) ALL\n%adm ALL = (ALL) ALL\nfred ALL = (ALL) NOPASSWD: ALL\nEOF\nchmod 0440 ${file}\nchown root ${file}\n")
         (test-resource-build
          [nil {:image [:ubuntu]}]
          (automated-admin-user "fred")))))
