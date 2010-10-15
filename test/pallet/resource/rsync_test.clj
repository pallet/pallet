(ns pallet.resource.rsync-test
  (:use pallet.resource.rsync)
  (:use [pallet.stevedore :only [script]]
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.core :as core]
   [pallet.utils :as utils] [pallet.stevedore :as stevedore]
   [pallet.resource :as resource]
   [pallet.resource.remote-file :as remote-file]
   [pallet.target :as target]
   [clojure.contrib.io :as io]
   [pallet.test-utils :as test-utils]))

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
         {tag node} nil
         [(resource/phase (rsync (.getPath dir) (.getPath target-dir) {}))]
         {:user user
          :middleware core/*middleware*})
        (let [target-tmp (java.io.File.
                          (str (.getPath target-dir)
                               "/" (.getName dir)
                               "/" (.getName tmp)))]
          (is (.canRead target-tmp))
          (is (= "text" (slurp (.getPath target-tmp))))
          (.delete target-tmp))
        (.delete target-dir)
        (core/lift*
         {tag node} nil
         [(resource/phase
           (rsync-directory (.getPath dir) (.getPath target-dir)))]
         {:user user
          :middleware core/*middleware*})
        (let [target-tmp (java.io.File.
                          (str (.getPath target-dir)
                               "/" (.getName dir)
                               "/" (.getName tmp)))]
          (is (.canRead target-tmp))
          (is (= "text" (slurp (.getPath target-tmp))))
          (.delete target-tmp))))))
