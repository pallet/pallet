(ns pallet.resource.remote-file
  "File Contents."
  (:use pallet.script
        pallet.stevedore
        [pallet.utils :only [cmd-join register-file-transfer!]]
        [pallet.resource :only [defcomponent]]
        [pallet.resource.file :only [adjust-file heredoc]]
        clojure.contrib.logging))

(defn remote-file*
  [path & options]
  (let [opts (merge {:action :create} (apply hash-map options))]
    (condp = (opts :action)
      :create
      (let [url (opts :url)
            content (opts :content)
            md5 (opts :md5)
            local-file (opts :local-file)
            remote-file (opts :remote-file)]
        (cmd-join
          [(cond
            (and url md5) (script
                           (if-not (file-exists? ~path)
                             (do (if-not
                                     (== ~md5 @(md5sum ~path "|" cut "-f1 -d' '"))
                                   (wget "-O" ~path ~url))))
                           (echo "MD5 sum is" @(md5sum ~path)))
            url (script
                 (wget "-O" ~path ~url)
                 (echo "MD5 sum is" @(md5sum ~path)))
            content (apply heredoc
                           path content
                           (apply concat (seq (select-keys opts [:literal]))))
            local-file (let [temp-path (register-file-transfer! local-file)]
                         (script (mv ~temp-path ~path)))
            remote-file (script (cp ~remote-file ~path))
            :else (throw
                   (IllegalArgumentException.
                    (str "Remote file " path " specified without content."))))
          (adjust-file path opts)])))))

(defcomponent remote-file "Remote file with contents management.
Options for specifying the file's content are:
  :url url          - download the specified url to the given filepath
  :content string   - use the specified content directly
  :local-file path  - use the file on the local machine at the given path
  :remote-file path - use the file on the remote machine at the given path
Options for specifying the file's permissions are:
  :owner user-name
  :group group-name
  :mode  file-mode"
  remote-file* [filepath & options])
