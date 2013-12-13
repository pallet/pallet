(ns pallet.ssh.node-state.state-root
  (:require
   [clojure.string :refer [split]]
   [clojure.test :refer :all]
   [pallet.local.execute :refer [local-script]]
   [pallet.ssh.node-state.state-root :refer :all]
   [pallet.test-utils :refer [with-bash-script-language
                              with-ubuntu-script-template]]
   [pallet.utils :refer [with-temporary tmpfile]]))

(use-fixtures :once
  with-bash-script-language
  with-ubuntu-script-template)

(deftest create-path-with-template-test
  (is (create-path-with-template "a/b/c/d" "/c/d")))

(deftest record-test
  (with-temporary [p (tmpfile)
                   c (tmpfile)]
    (spit p "test")
    (local-script ~(record (str p) (str c) {}))
    (is (= "test" (slurp c)))))

(deftest record-md5-test
  (with-temporary [p (tmpfile)
                   m (tmpfile)]
    (spit p "test")
    (local-script ~(record-md5 (str p) (str m)))
    (is (= "098f6bcd4621d373cade4e832627b4f6"
           (first (split (slurp m) #" "))))))

(deftest verify-test
  (with-temporary [p (tmpfile)
                   c (tmpfile)
                   m (tmpfile)]
    (spit p "test")
    (is (zero? (:exit (local-script
                       (do
                         ~(record (str p) (str c) {})
                         ~(record-md5 (str p) (str m))
                         ~(verify (str p) (str c) (str m)))))))
    (spit p "test1")
    (is (pos? (:exit (local-script ~(verify (str p) (str c) (str m)))))
        "mismatched content should error")
    (spit c "test1")
    (is (pos? (:exit (local-script ~(verify (str p) (str c) (str m)))))
        "mismatched md5 should error")))
