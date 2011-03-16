(ns pallet.resource.rsync-test
  (:use pallet.resource.rsync)
  (:use [pallet.stevedore :only [script]]
        clojure.test)
  (:require
   [pallet.action :as action]
   [pallet.core :as core]
   [pallet.resource.remote-file :as remote-file]
   [pallet.stevedore :as stevedore]
   [pallet.target :as target]
   [pallet.phase :as phase]
   [pallet.test-utils :as test-utils]
   [pallet.utils :as utils]
   [clojure.contrib.io :as io]))

(deftest rsync-test
  (core/with-admin-user (assoc utils/*admin-user*
                          :username (test-utils/test-username))
    (utils/with-temporary [dir (utils/tmpdir)
                           tmp (utils/tmpfile dir)
                           target-dir (utils/tmpdir)]
      ;; this is convoluted to get around the "t" sticky bit on temp dirs
      (let [user (assoc utils/*admin-user*
                   :username (test-utils/test-username) :no-sudo true)
            node (test-utils/make-localhost-node :tag "tag")]
        (io/copy "text" tmp)
        (core/defnode tag {:packager :no-packages})
        (.delete target-dir)
        (core/lift*
         {:node-set {tag node}
          :phase-list [(phase/phase-fn
                        (rsync (.getPath dir) (.getPath target-dir) {}))]
          :environment
          {:user user
           :middleware core/*middleware*
           :algorithms {:lift-fn core/sequential-lift}}})
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
          :phase-list [(phase/phase-fn
                        (rsync-directory (.getPath dir) (.getPath target-dir)))]
          :environment
          {:user user
           :middleware core/*middleware*
           :algorithms {:lift-fn core/sequential-lift}}})
        (let [target-tmp (java.io.File.
                          (str (.getPath target-dir)
                               "/" (.getName dir)
                               "/" (.getName tmp)))]
          (is (.canRead target-tmp))
          (is (= "text" (slurp (.getPath target-tmp))))
          (.delete target-tmp))))))
