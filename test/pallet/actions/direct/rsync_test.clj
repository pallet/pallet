(ns pallet.actions.direct.rsync-test
  (:require
   [clojure.test :refer :all]
   [pallet.actions.direct.rsync :refer [rsync* rsync-to-local*]]
   [pallet.stevedore :as stevedore]
   [pallet.test-utils
    :refer [with-bash-script-language with-ubuntu-script-template
            with-no-source-line-comments]]))

(use-fixtures :once
   with-ubuntu-script-template
   with-bash-script-language
   with-no-source-line-comments)

(deftest rsync*-test
  (is (=
       (stevedore/checked-script
        "rsync file1 to /dest/file1"
        ("/usr/bin/rsync"
         -e "'/usr/bin/ssh -o \"StrictHostKeyChecking no\" -o \"NumberOfPasswordPrompts 0\" -p 22'"
         -F -F -r --delete --copy-links
         --rsync-path "\"/usr/bin/sudo -u root rsync\""
         --owner --perms file1
         "fred@1.2.3.4:/dest/file1"))
       (rsync* {}
               "file1" "/dest/file1"
               {:ip "1.2.3.4" :port 22 :username "fred"}))))
