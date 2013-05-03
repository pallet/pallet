(ns pallet.actions.direct.rsync-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [pallet.actions :refer [rsync rsync-directory]]
   [pallet.algo.fsmop :refer [complete?]]
   [pallet.api :refer [group-spec lift plan-fn with-admin-user]]
   [pallet.common.logging.logutils :refer [logging-threshold-fixture]]
   [pallet.core.user :refer [*admin-user*]]
   [pallet.test-utils :refer [make-localhost-compute test-username]]
   [pallet.utils :as utils]))

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
                  :environment {:user user}
                  :async true)
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
                   :environment {:user user}
                   :async true)
              target-tmp (java.io.File.
                          (str (.getPath target-dir)
                               "/" (.getName dir)
                               "/" (.getName tmp)))]
          @op
          (is (complete? op))
          (is (.canRead target-tmp))
          (is (= "text" (slurp (.getPath target-tmp))))
          (.delete target-tmp))))))
