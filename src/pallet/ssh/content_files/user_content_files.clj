(ns pallet.ssh.content-files.user-content-files
  (:require
   [pallet.core.session :refer [effective-user]]
   [pallet.core.user :refer [effective-username]]
   [pallet.script.lib :refer [file tmp-dir]]
   [pallet.ssh.content-files.protocols :refer [ContentFiles]]
   [pallet.stevedore :refer [fragment]]
   [pallet.utils :refer [base64-md5]]))

(deftype UserContentFiles [content-root]
  ContentFiles
  (content-path [cp session action-options path]
    (fragment
     (file ~(content-root)
           ~(effective-username (effective-user session))
           ~(base64-md5 path)))))

(defn user-content-files
  [{:keys [content-root]}]
  (UserContentFiles. (fn [] (or content-root (fragment (tmp-dir))))))
