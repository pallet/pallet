(ns pallet.core.file-upload.rsync-upload-test
  (:require
   [clojure.test :refer :all]
   [pallet.core.file-upload.rsync-upload :refer :all]
   [pallet.local.execute :refer [local-script-context]]
   [pallet.test-utils :refer [test-username]]
   [pallet.utils :refer [with-temp-file tmpdir tmpfile]]))

(deftest rsync-upload-file-test
  (let [ip "127.0.0.1"
        username (test-username)
        content "test"]
    (local-script-context
     (with-temp-file [local-f content]
       (let [target-f (tmpfile)]
         (.delete target-f)
         (rsync-upload-file (str local-f) (str target-f) ip 22 username {})
         (is (= content (slurp target-f)) "target has correct content")
         (.delete target-f))))))

;; (deftest rsync-ensure-dir-test
;;   (let [endpoint {:server "127.0.0.1"}
;;         auth {:user (assoc *admin-user* :username (test-username))}
;;         content "test"
;;         connection (transport/open ssh-connection endpoint auth {:max-tries 3})]
;;     (try
;;       (let [d (tmpdir)
;;             f (io/file d "user" "a")]
;;         (rsync-ensure-dir connection (str f))
;;         (.isDirectory (.getParentFile f))
;;         (.delete (.getParentFile f)))
;;       (finally
;;         (transport/release ssh-connection endpoint auth {:max-tries 3})))))
