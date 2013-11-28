(ns pallet.actions.direct.remote-file
  "Action to specify remote file content."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [pallet.action :refer [action-options implement-action]]
   [pallet.action-plan :as action-plan]
   [pallet.actions
    :refer [content-options
            delete-local-path
            transfer-file
            transfer-file-to-local
            wait-for-file]]
   [pallet.actions-impl
    :refer [copy-filename md5-filename new-filename upload-filename
            remote-file-action]]
   [pallet.actions.direct.file :as file]
   [pallet.blobstore :as blobstore]
   [pallet.core.file-upload :refer [upload-file upload-file-path]]
   [pallet.environment-impl :refer [get-for]]
   [pallet.script.lib :as lib
    :refer [canonical-path chgrp chmod chown dirname exit path-group path-mode
            path-owner user-default-group]]
   [pallet.script.lib :refer [wait-while]]
   [pallet.ssh.file-upload.sftp-upload :refer [sftp-upload]]
   [pallet.ssh.node-state
    :refer [new-file-content record-checksum verify-checksum]]
   [pallet.ssh.node-state.state-root :refer [state-root-node-state]]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore :refer [fragment script]]
   [pallet.template :as templates]
   [pallet.utils :refer [first-line]]))

(def default-file-uploader (sftp-upload {}))
(def default-node-state (state-root-node-state {}))

(defn file-uploader
  [action-options]
  (or (:file-uploader action-options) default-file-uploader))

(implement-action delete-local-path :direct
                  {:action-type :fn/clojure :location :origin}
                  [session ^java.io.File local-path]
  [(fn [session]
     (.delete (io/file local-path))
     [local-path session])
   session])

(implement-action transfer-file-to-local :direct
                  {:action-type :transfer/to-local :location :origin}
                  [session remote-path local-path]
  [[(.getPath (io/file remote-path))
    (.getPath (io/file local-path))]
   session])

(implement-action transfer-file :direct
                  {:action-type :transfer/from-local :location :origin}
                  [session local-path remote-path]
  [[(.getPath (io/file local-path))
    (.getPath (io/file remote-path))
    (fn []
      ;; return function that will do the upload
      (let [action-options (action-options session)
            uploader (or (:file-uploader action-options)
                         default-file-uploader)]
        (upload-file uploader session
                     (.getPath (io/file local-path))
                     (.getPath (io/file remote-path))
                     action-options)))]
   session])

(defn- summarise-content
  [m]
  (if (:content m)
    (update-in m [:content] #(str (first-line %) "..."))
    m))

(implement-action remote-file-action :direct
                  {:action-type :script :location :target}
                  [session path {:keys [action url local-file remote-file link
                                        content literal
                                        template values
                                        md5 md5-url
                                        owner group mode force
                                        blob blobstore
                                        install-new-files
                                        overwrite-changes
                                        no-versioning max-versions
                                        flag-on-changed
                                        force
                                        insecure
                                        verify]
                                 :or {action :create max-versions 5
                                      install-new-files true}
                                 :as options}]
  [[{:language :bash
     :summary (str "remote-file " path " "
                   (string/join
                    " "
                    (->> (select-keys options content-options)
                         (summarise-content)
                         (apply concat)
                         (map pr-str))))}
    (let [action-options (action-options session)
          uploader (file-uploader action-options)
          node-state (or (:node-state action-options) default-node-state)

          new-path (upload-file-path uploader session path action-options)
          md5-path (str new-path ".md5")

          proxy (get-for session [:proxy] nil)
          options (if (and owner (not group))
                    (assoc options
                      :group (fragment @(user-default-group ~owner)))
                    options)]
      (case action
        :create
        (action-plan/checked-commands
         (str "remote-file " path)

         ;; check for local modifications
         (if overwrite-changes
           ""
           (verify-checksum node-state session path))

         ;; Create the new content
         (cond
          (and url md5) (stevedore/chained-script
                         (if (chain-or (not (file-exists? ~path))
                                       (!= ~md5 @((pipe
                                                   (~lib/md5sum ~path)
                                                   (~lib/cut ""
                                                             :fields 1
                                                             :delimiter " ")))))
                           ~(stevedore/chained-script
                             (~lib/download-file
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
                                 (if-not (~lib/md5sum-verify @newmd5path)
                                   (do
                                     (println ~(str "Download of " url
                                                    " failed to match md5"))
                                     (lib/rm @tmpdir
                                             :force ~true :recursive ~true)
                                     (lib/exit 1)))))
                             (lib/rm @tmpdir :force ~true :recursive ~true))
          url (stevedore/chained-script
               (~lib/download-file
                ~url ~new-path :proxy ~proxy :insecure ~insecure))
          content (stevedore/script
                   (~lib/heredoc
                    ~new-path ~content ~(select-keys options [:literal])))
          local-file (do)               ; file already in place at new-path
          remote-file (stevedore/script
                       (~lib/cp ~remote-file ~new-path :force ~true))
          template (stevedore/script
                    (~lib/heredoc
                     ~new-path
                     ~(templates/interpolate-template
                       template (or values {}) session)
                     ~(select-keys options [:literal])))
          link (stevedore/script
                (~lib/ln ~link ~path :force ~true :symbolic ~true))
          blob (action-plan/checked-script
                "Download blob"
                (~lib/download-request
                 ~new-path
                 ~(blobstore/sign-blob-request
                   (or blobstore (get-for session [:blobstore] nil)
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
                (~lib/diff ~path ~new-path :unified true)
                (set! contentdiff "$?")))

            ;; install the file if the content is different
            (if (&& (not (== @contentdiff 0)) (file-exists? ~new-path))
              ~(stevedore/chain-commands
                ;; adjust ownership/permissions before putting the
                ;; file in place
                (file/adjust-file new-path options)
                (script (lib/mv ~new-path ~path :force ~true))
                (if flag-on-changed
                  (script (lib/set-flag ~flag-on-changed)))
                (new-file-content
                 node-state session path
                 (select-keys
                  options [:max-versions :no-versioning :versioning]))
                (record-checksum node-state session path))))))

        :delete (action-plan/checked-script
                 (str "delete remote-file " path)
                 (~lib/rm ~path :force ~force))))]
   session])


(implement-action wait-for-file :direct
  {:action-type :script :location :target}
  [session path & {:keys [action max-retries standoff service-name]
                   :or {action :create
                        max-retries 5 standoff 2}
                   :as options}]
  [[{:language :bash}
    (let [[test-expr waiting-msg failed-msg]
          (case action
            :create [(fragment (not (file-exists? ~path)))
                     (str "Waiting for " path " to exist")
                     (str "Failed waiting for " path " to exist")]
            :remove [(fragment (file-exists? ~path))
                     (str "Waiting for " path " to be removed")
                     (str "Failed waiting for " path " to be removed")])]
      (wait-while test-expr standoff max-retries waiting-msg failed-msg))]
   session])
