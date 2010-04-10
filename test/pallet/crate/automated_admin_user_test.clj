(ns pallet.crate.automated-admin-user-test
  (:use [pallet.crate.automated-admin-user] :reload-all)
  (:require [pallet.template :only [apply-templates]]
            [pallet.resource :only [build-resources]])
  (:use clojure.test
        pallet.test-utils
        [clojure.contrib.java-utils :only [file]]))

(with-private-vars [pallet.crate.authorize-key
                    []])

(deftest automated-admin-user-test
  (is (= "if getent passwd fred; then usermod --shell /bin/bash fred;else useradd --shell /bin/bash --create-home fred;fi\nmkdir -p $(getent passwd fred | cut -d: -f6)/.ssh\nchown  fred $(getent passwd fred | cut -d: -f6)/.ssh\nchmod  755 $(getent passwd fred | cut -d: -f6)/.ssh\nfile=$(getent passwd fred | cut -d: -f6)/.ssh/authorized_keys\ncat > ${file} <<EOF\nssh-rsa AAAAB3NzaC1yc2EAAAABIwAAAQEA5EoGFoDkZI2LTbf0o4quVEowEiMqpAcIkXdT632RWUk+LzkYYCHxHEBPs/szA4DiIOKEnDDxLeHWZOUaz4xZ3Ag/3UZ60yaYuag6j2697CpwMaIuRhCdS6bTmmDPqPOiOvCiimMe/mq16BRRPzd5Ss5/6ztvDqV5RkTYVCeJEewQ48PAQT+TA9s8rJp1r41US4Go1ivJqonmhzjeW1TVBp2o84Hloh052NKxfMcKeToCStgGrlGGMw+WmXe1zQTFznTBh2GQubXN06XoTBRoVoxEpVXszG3pxyGsSX6HA8E+GYyuBLDHkTkxkVwiGV1X+RkeS0anx9Z+swk8jPsCHw== duncan@cyclops\n\nEOF\nchmod 0644 ${file}\nchown fred ${file}\nfile=/etc/sudoers\ncat > ${file} <<EOF\nroot ALL = (ALL) ALL\n%wheel ALL = (ALL) ALL\nfred ALL = (ALL) NOPASSWD: ALL\nEOF\nchmod 0440 ${file}\nchown root ${file}\n"
         (pallet.resource/build-resources [] (automated-admin-user "fred")))))
