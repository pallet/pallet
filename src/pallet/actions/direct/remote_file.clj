(ns pallet.actions.direct.remote-file
  "Action to specify remote file content."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [pallet.action :refer [implement-action]]
   [pallet.actions
    :refer [content-options
            transfer-file
            transfer-file-to-local
            wait-for-file]]
   [pallet.actions.decl
    :refer [checked-commands checked-script remote-file-action]]
   [pallet.actions.direct.file :as file]
   [pallet.blobstore :as blobstore]
   [pallet.core.file-upload
    :refer [upload-file upload-file-path user-file-path]]
   [pallet.script.lib :as lib
    :refer [canonical-path chgrp chmod chown dirname exit mkdir
            path-group path-mode path-owner user-default-group]]
   [pallet.script.lib :refer [wait-while]]
   [pallet.ssh.file-upload.sftp-upload :refer [sftp-upload]]
   [pallet.ssh.node-state
    :refer [new-file-content record-checksum verify-checksum]]
   [pallet.ssh.node-state.state-root
    :refer [state-root-backup state-root-checksum]]
   [pallet.stevedore :as stevedore :refer [fragment script]]
   [pallet.utils :refer [first-line]]))

(def default-file-uploader (sftp-upload {}))
(def default-checksum (state-root-checksum {}))
(def default-backup (state-root-backup {}))

(defn file-uploader
  [action-options]
  (or (:file-uploader action-options) default-file-uploader))

(defn transfer-file-to-local*
  [{:keys [options]} remote-path local-path]
  {:remote-path (.getPath (io/file remote-path))
   :local-path (.getPath (io/file local-path))})

(implement-action transfer-file-to-local :direct
  {:action-type :transfer/to-local} transfer-file-to-local*)

(defn transfer-file*
  [{:keys [options]} local-path remote-path]
  {:local-path (.getPath (io/file local-path))
   :remote-path (.getPath (io/file remote-path))
   :f (fn [target]
        ;; return function that will do the upload
        (let [uploader (or (:file-uploader options)
                           default-file-uploader)]
          (upload-file uploader
                       target
                       (.getPath (io/file local-path))
                       (.getPath (io/file remote-path))
                       options)))})

(implement-action transfer-file :direct
  {:action-type :transfer/from-local} transfer-file*)

(defn- summarise-content
  [m]
  (if (:content m)
    (update-in m [:content] #(str (first-line %) "..."))
    m))


(defn remote-file*
  [action-state
   path {:keys [action url local-file remote-file link
                content literal
                template values
                md5 md5-url
                owner group mode force
                blob blobstore
                install-new-files
                overwrite-changes no-versioning max-versions
                flag-on-changed
                force
                insecure
                verify
                proxy]
         :or {action :create max-versions 5
              install-new-files true}
         :as options}]
  [{:language :bash
    :summary (str "remote-file " path " "
                  (string/join
                   " "
                   (->> (select-keys options content-options)
                        (summarise-content)
                        (apply concat)
                        (map pr-str))))}
   (let [action-options (:options action-state)
         uploader (or (:file-uploader action-options) default-file-uploader)
         file-checksum (or (:file-checksum action-options) default-checksum)
         file-backup (or (:file-backup action-options) default-backup)

         new-path (if local-file
                    (upload-file-path uploader path action-options)
                    (user-file-path uploader path action-options))
         md5-path (str new-path ".md5")

         options (if (and owner (not group))
                   (assoc options
                     :group (fragment @(user-default-group ~owner)))
                   options)]
     (case action
       :create
       (checked-commands
        (str "remote-file " path)

        ;; check for local modifications
        (if overwrite-changes
          ""
          (verify-checksum file-checksum action-options path))

        ;; create upload dir if needed
        (stevedore/script
         (mkdir @(dirname ~new-path) :path true))

        ;; Create the new content
        (cond
         (and url md5) (stevedore/chained-script
                        (if (chain-or (not (file-exists? ~path))
                                      (!= ~md5 @((pipe
                                                  (lib/md5sum ~path)
                                                  (lib/cut ""
                                                            :fields 1
                                                            :delimiter " ")))))
                          ~(stevedore/chained-script
                            (lib/download-file
                             ~url ~new-path
                             :proxy ~proxy :insecure ~insecure))))
         ;; Download md5 to temporary directory.
         (and url md5-url) (stevedore/chained-script
                            (var tmpdir (quoted (lib/make-temp-dir "rf")))
                            (var basefile
                                 (quoted
                                  (str @tmpdir "/" @(lib/basename ~path))))
                            (var newmd5path (quoted (str @basefile ".md5")))
                            (lib/download-file
                             ~md5-url @newmd5path :proxy ~proxy
                             :insecure ~insecure)
                            (lib/normalise-md5 @newmd5path)
                            (if (chain-or (not (file-exists? ~md5-path))
                                          (not (lib/diff @newmd5path ~md5-path)))
                              (do
                                (lib/download-file
                                 ~url ~new-path :proxy ~proxy
                                 :insecure ~insecure)
                                (lib/ln ~new-path @basefile)
                                (if-not (lib/md5sum-verify @newmd5path)
                                  (do
                                    (println ~(str "Download of " url
                                                   " failed to match md5"))
                                    (lib/rm @tmpdir
                                            :force ~true :recursive ~true)
                                    (lib/exit 1)))))
                            (lib/rm @tmpdir :force ~true :recursive ~true))
         url (stevedore/chained-script
              (lib/download-file
               ~url ~new-path :proxy ~proxy :insecure ~insecure))
         content (stevedore/script
                  (lib/heredoc
                   ~new-path ~content ~(select-keys options [:literal])))
         local-file (stevedore/script
                     ;; file should be in place at new-path
                     (if-not (file-exists? ~new-path)
                       (lib/exit 2)))
         remote-file (stevedore/script
                      (lib/cp ~remote-file ~new-path :force ~true))
         ;; template (stevedore/script
         ;;           (lib/heredoc
         ;;            ~new-path
         ;;            ~(templates/interpolate-template
         ;;              template (or values {}) session)
         ;;            ~(select-keys options [:literal])))
         link (stevedore/script
               (lib/ln ~link ~path :force ~true :symbolic ~true))
         blob (checked-script
               "Download blob"
               (lib/download-request
                ~new-path
                ~(blobstore/sign-blob-request
                  (or blobstore
                      (throw (IllegalArgumentException.
                              "No :blobstore given for blob content.") ))
                  (:container blob) (:path blob)
                  {:method :get})))
         :else (throw
                (IllegalArgumentException.
                 (str "remote-file " path " specified without content."))))

        ;; process the new file accordingly
        (when verify
          (stevedore/checked-script
           (str "Verify " new-path " with " verify)
           (~verify ~new-path)))

        (when (and install-new-files (not link))
          (stevedore/chained-script
           ;; get the diff between current and new
           (var contentdiff "")
           (if (&& (file-exists? ~path) (file-exists? ~new-path))
             (do
               (lib/diff ~path ~new-path :unified true)
               (set! contentdiff "$?")))

           ;; install the file if the content is different
           (if (&& (not (== @contentdiff 0)) (file-exists? ~new-path))
             ~(stevedore/chain-commands
               ;; adjust ownership/permissions before putting the
               ;; file in place
               (script (lib/cp ~new-path ~path :force ~true))
               (file/adjust-file path options)
               (if flag-on-changed
                 (script (lib/set-flag ~flag-on-changed)))
               (new-file-content
                file-backup action-options path
                (select-keys
                 options [:max-versions :no-versioning :versioning]))
               (record-checksum file-checksum action-options path))))))

       :delete (checked-script
                (str "delete remote-file " path)
                (lib/rm ~path :force ~force))))])

(implement-action remote-file-action :direct {} remote-file*)

(defn wait-for-file*
  [action-state
   path {:keys [action max-retries standoff service-name]
         :or {action :create
              max-retries 5 standoff 2}
         :as options}]
  (let [[test-expr waiting-msg failed-msg]
        (case action
          :create [(fragment (not (file-exists? ~path)))
                   (str "Waiting for " path " to exist")
                   (str "Failed waiting for " path " to exist")]
          :remove [(fragment (file-exists? ~path))
                   (str "Waiting for " path " to be removed")
                   (str "Failed waiting for " path " to be removed")])]
    (wait-while test-expr standoff max-retries waiting-msg failed-msg)))

(implement-action wait-for-file :direct {}
  [{:language :bash}] wait-for-file*)
