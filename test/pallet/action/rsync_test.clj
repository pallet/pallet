(ns pallet.action.rsync-test
  (:use pallet.action.rsync)
  (:use [pallet.stevedore :only [script]]
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
         (test-utils/test-session
          {:node-set {tag #{node}}
           :phase-list [:p]
           :inline-phases {:p (rsync (.getPath dir) (.getPath target-dir) {})}
           :environment
           {:user user
            :middleware core/*middleware*
            :executor core/default-executor
            :algorithms (assoc core/default-algorithms
                          :lift-fn core/sequential-lift)}}))
        (let [target-tmp (java.io.File.
                          (str (.getPath target-dir)
                               "/" (.getName dir)
                               "/" (.getName tmp)))]
          (is (.canRead target-tmp))
          (is (= "text" (slurp (.getPath target-tmp))))
          (.delete target-tmp))
        (.delete target-dir)
        (core/lift*
         (test-utils/test-session
          {:node-set {tag node}
           :phase-list [:p]
           :inline-phases {:p
                           (rsync-directory
                            (.getPath dir) (.getPath target-dir))}
           :environment
           {:user user
            :middleware core/*middleware*
            :executor core/default-executor
            :algorithms (assoc core/default-algorithms
                          :lift-fn core/sequential-lift)}}))
        (let [target-tmp (java.io.File.
                          (str (.getPath target-dir)
                               "/" (.getName dir)
                               "/" (.getName tmp)))]
          (is (.canRead target-tmp))
          (is (= "text" (slurp (.getPath target-tmp))))
          (.delete target-tmp))))))
