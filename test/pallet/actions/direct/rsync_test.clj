(ns pallet.actions.direct.rsync-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [pallet.actions :refer [rsync rsync-directory]]
   [pallet.actions.direct.rsync :refer [rsync* rsync-to-local*]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.group :refer [group-spec lift phase-errors]]
   [pallet.plan :refer [plan-fn]]
   [pallet.stevedore :as stevedore :refer [fragment]]
   [pallet.test-utils
    :refer [make-localhost-compute test-username
            with-bash-script-language with-ubuntu-script-template
            with-no-source-line-comments]]
   [pallet.user :refer [*admin-user* with-admin-user]]
   [pallet.utils :as utils]))

(use-fixtures :once
   with-ubuntu-script-template
   with-bash-script-language
   with-no-source-line-comments
  (logging-threshold-fixture))

(deftest rsync*-test
  (is (script-no-comment=
       (stevedore/checked-script
        "rsync file1 to /dest/file1"
        ("/usr/bin/rsync"
         -e "'/usr/bin/ssh -o \"StrictHostKeyChecking no\" -p 22'"
         -F -F --ip "\"1.2.3.4\"" -r --delete --copy-links
         --rsync-path "\"/usr/bin/sudo -u root rsync\""
         --owner --perms file1
         "fred@1.2.3.4:/dest/file1"))
       (rsync* {}
               "file1" "/dest/file1"
               {:ip "1.2.3.4" :port 22 :username "fred"}))))


(deftest rsync-test
  (with-admin-user (assoc *admin-user*
                     :username (test-username))
    (utils/with-temporary [dir (utils/tmpdir)
                           tmp (utils/tmpfile dir)
                           target-dir (utils/tmpdir)]
      ;; this is convoluted to get around the "t" sticky bit on temp dirs
      (let [user (assoc *admin-user*
                   :username (test-username) :no-sudo true)
            compute (make-localhost-compute :group-name "tag")
            tag (group-spec "tag" :packager :no-packages)]
        (io/copy "text" tmp)
        (.delete target-dir)

        (let [op (lift
                  tag
                  :compute compute
                  :phase (plan-fn [session]
                           (rsync
                            session (.getPath dir) (.getPath target-dir) {}))
                  :environment {:user user})
              target-tmp (java.io.File.
                          (str (.getPath target-dir)
                               "/" (.getName dir)
                               "/" (.getName tmp)))]
          (is (not (phase-errors op)))
          (is (.canRead target-tmp))
          (is (= "text" (slurp (.getPath target-tmp))))
          (.delete target-tmp))
        (.delete target-dir)
        (let [op (lift
                   tag
                   :compute compute
                   :phase (plan-fn [session]
                            (rsync-directory
                             session
                             (.getPath dir) (.getPath target-dir)))
                   :environment {:user user})
              target-tmp (java.io.File.
                          (str (.getPath target-dir)
                               "/" (.getName dir)
                               "/" (.getName tmp)))]
          op
          (is (not (phase-errors op)))
          (is (.canRead target-tmp))
          (is (= "text" (slurp (.getPath target-tmp))))
          (.delete target-tmp))))))
