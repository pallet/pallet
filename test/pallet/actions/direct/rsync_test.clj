(ns pallet.actions.direct.rsync-test
  (:use
   clojure.test
   [pallet.actions :only [rsync rsync-directory]]
   [pallet.algo.fsmop :only [complete?]]
   [pallet.api :only [group-spec lift plan-fn with-admin-user]]
   [pallet.core.user :only [*admin-user*]]
   [pallet.common.logging.logutils :only [logging-threshold-fixture]]
   [pallet.stevedore :only [script]]
   [pallet.test-utils :only [make-localhost-compute test-username]])
  (:require
   pallet.actions.direct.rsync
   [pallet.action :as action]
   [pallet.phase :as phase]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [clojure.java.io :as io]))

(use-fixtures :once (logging-threshold-fixture))

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
                  :phase (plan-fn
                           (rsync (.getPath dir) (.getPath target-dir) {}))
                  :environment {:user user})
              target-tmp (java.io.File.
                          (str (.getPath target-dir)
                               "/" (.getName dir)
                               "/" (.getName tmp)))]
          @op
          (is (complete? op))
          (is (.canRead target-tmp))
          (is (= "text" (slurp (.getPath target-tmp))))
          (.delete target-tmp))
        (.delete target-dir)
        (let [op (lift
                   tag
                   :compute compute
                   :phase (plan-fn
                            (rsync-directory
                             (.getPath dir) (.getPath target-dir)))
                   :environment {:user user})
              target-tmp (java.io.File.
                          (str (.getPath target-dir)
                               "/" (.getName dir)
                               "/" (.getName tmp)))]
          @op
          (is (complete? op))
          (is (.canRead target-tmp))
          (is (= "text" (slurp (.getPath target-tmp))))
          (.delete target-tmp))))))
