(ns pallet.resource.rsync-test
  (:use pallet.resource.rsync)
  (:use [pallet.stevedore :only [script]]
        clojure.test
        pallet.test-utils)
  (:require
   [pallet.core :as core]
   [pallet.utils :as utils]
   [pallet.compute :as compute]
   [pallet.stevedore :as stevedore]
   [pallet.resource :as resource]
   [pallet.resource.remote-file :as remote-file]
   [pallet.target :as target]
   [clojure.contrib.io :as io]))

(defn test-username
  "Function to get test username. This is a function to avoid issues with AOT."
  [] (or (. System getProperty "ssh.username")
         (. System getProperty "user.name")))

(deftest rsync-test
  (with-temporary [dir (tmpdir)
                   tmp (tmpfile dir)
                   target-dir (tmpdir)]
    ;; this is convoluted to get around the "t" sticky bit on temp dirs
    (let [user (assoc utils/*admin-user*
                 :username (test-username) :no-sudo true)
          node (compute/make-unmanaged-node
                "tag" "localhost"
                :operating-system (compute/local-operating-system))]
      (io/copy "text" tmp)
      (core/defnode tag [:no-packages])

      (core/lift*
       nil "" {tag node} nil
       [(resource/phase (rsync (.getPath dir) (.getPath target-dir) {}))]
       {:user user}
       core/*middleware*)
      (let [target-tmp (java.io.File.
                        (str (.getPath target-dir)
                             "/" (.getName dir)
                             "/" (.getName tmp)))]
        (is (.canRead target-tmp))
        (is (= "text" (slurp (.getPath target-tmp))))
        (.delete target-tmp))
      (core/lift*
       nil "" {tag node} nil
       [(resource/phase
         (rsync-directory (.getPath dir) (.getPath target-dir)))]
       {:user user}
       core/*middleware*)
            (let [target-tmp (java.io.File.
                        (str (.getPath target-dir)
                             "/" (.getName dir)
                             "/" (.getName tmp)))]
        (is (.canRead target-tmp))
        (is (= "text" (slurp (.getPath target-tmp))))
        (.delete target-tmp)))))
