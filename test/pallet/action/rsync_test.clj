(ns pallet.action.rsync-test
  (:use pallet.action.rsync)
  (:use
   [pallet.build-actions :only [build-session]]
   [pallet.stevedore :only [script]]
   clojure.test)
  (:require
   [pallet.action :as action]
   [pallet.action.remote-file :as remote-file]
   [pallet.common.logging.logutils :as logutils]
   [pallet.core :as core]
   [pallet.phase :as phase]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target]
   [pallet.test-utils :as test-utils]
   [pallet.utils :as utils]
   [clojure.java.io :as io]))

(use-fixtures :once (logutils/logging-threshold-fixture))

(deftest rsync-command-test
  (testing "default"
    (is (= (str "/usr/bin/rsync "
                "-e '/usr/bin/ssh -o \"StrictHostKeyChecking no\" -p 22' "
                "-rP --delete --copy-links -F -F "
                "/from null@127.0.0.1:/to")
           (rsync-command
            (build-session
             {:server {:node (test-utils/make-localhost-node)}})
            "/from" "/to" {}))))
  (testing "port from node"
    (is (= (str "/usr/bin/rsync "
                "-e '/usr/bin/ssh -o \"StrictHostKeyChecking no\" -p 2222' "
                "-rP --delete --copy-links -F -F "
                "/from null@127.0.0.1:/to")
           (rsync-command
            (build-session
             {:server {:node (test-utils/make-localhost-node :ssh-port 2222)}})
            "/from" "/to" {}))))
  (testing "options"
    (is (= (str "/usr/bin/rsync "
                "-e '/usr/bin/ssh -o \"StrictHostKeyChecking no\" -p 22' "
                "-rP --delete --copy-links -F -F --times "
                "/from null@127.0.0.1:/to")
           (rsync-command
            (build-session
             {:server {:node (test-utils/make-localhost-node)}})
            "/from" "/to" {:times true})))
    (is (= (str "/usr/bin/rsync "
                "-e '/usr/bin/ssh -o \"StrictHostKeyChecking no\" -p 22' "
                "-rP --delete --copy-links -F -F -t "
                "/from null@127.0.0.1:/to")
           (rsync-command
            (build-session
             {:server {:node (test-utils/make-localhost-node)}})
            "/from" "/to" {:t true})))))

(deftest rsync-test
  (core/with-admin-user (assoc utils/*admin-user*
                          :username (test-utils/test-username))
    (utils/with-temporary [dir (utils/tmpdir)
                           tmp (utils/tmpfile dir)
                           target-dir (utils/tmpdir)]
      ;; this is convoluted to get around the "t" sticky bit on temp dirs
      (let [user (assoc utils/*admin-user*
                   :username (test-utils/test-username) :no-sudo true)
            node (test-utils/make-localhost-node :tag "tag")
            tag (core/group-spec "tag" :packager :no-packages)]
        (io/copy "text" tmp)
        (.delete target-dir)
        (core/lift*
         {:node-set {tag #{node}}
          :phase-list [:p]
          :inline-phases {:p (phase/phase-fn
                              (rsync (.getPath dir) (.getPath target-dir) {}))}
          :environment
          {:user user
           :middleware core/*middleware*
           :executor core/default-executors
           :algorithms (assoc core/default-algorithms
                         :lift-fn core/sequential-lift)}})
        (let [target-tmp (java.io.File.
                          (str (.getPath target-dir)
                               "/" (.getName dir)
                               "/" (.getName tmp)))]
          (is (.canRead target-tmp))
          (is (= "text" (slurp (.getPath target-tmp))))
          (.delete target-tmp))
        (.delete target-dir)
        (core/lift*
         {:node-set {tag node}
          :phase-list [:p]
          :inline-phases {:p (phase/phase-fn
                              (rsync-directory
                               (.getPath dir) (.getPath target-dir)))}
          :environment
          {:user user
           :middleware core/*middleware*
           :executor core/default-executors
           :algorithms (assoc core/default-algorithms
                         :lift-fn core/sequential-lift)}})
        (let [target-tmp (java.io.File.
                          (str (.getPath target-dir)
                               "/" (.getName dir)
                               "/" (.getName tmp)))]
          (is (.canRead target-tmp))
          (is (= "text" (slurp (.getPath target-tmp))))
          (.delete target-tmp))))))
