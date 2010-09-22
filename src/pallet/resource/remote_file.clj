(ns pallet.resource.remote-file
  "File Contents."
  (:require
   [pallet.resource :as resource]
   [pallet.stevedore :as stevedore]
   [pallet.template :as templates]
   [pallet.resource.file :as file]
   [pallet.resource.directory :as directory]
   [pallet.utils :as utils]
   [org.jclouds.blobstore :as jclouds-blobstore]
   [clojure.contrib.def :as def]))

(def install-new-files true)

(defn set-install-new-files
  "Set boolean flag to control installation of new files"
  [flag]
  (alter-var-root #'install-new-files (fn [_] flag)))

(def/defvar
  content-options
  [:local-file :remote-file :url :md5 :content :literal :template :values
   :action :blob :blobstore]
  "A vector of the options accepted by remote-file.  Can be used for option
  forwarding when calling remote-file from other crates.")

(def/defvar
  all-options
  [:local-file :remote-file :url :md5 :content :literal :template :values
   :action :owner :group :mode]
  "A vector of the options accepted by remote-file.  Can be used for option
  forwarding when calling remote-file from other crates.")

(defn get-request
  [request]
  (stevedore/script
   (curl -s "--retry" 20
         ~(apply str (map
                      #(format "-H \"%s: %s\" " (first %) (second %))
                      (.. request getHeaders entries)))
         ~(.. request getEndpoint toASCIIString))))


(resource/defresource remote-file
  "Remote file with contents management.
Options for specifying the file's content are:
  :url url          - download the specified url to the given filepath
  :content string   - use the specified content directly
  :local-file path  - use the file on the local machine at the given path
  :remote-file path - use the file on the remote machine at the given path
  :link             - file to link to:
  :literal          - prevent shell expansion on content
  :md5              - md5 for file
  :md5-url          - a url containing file's md5
  :template         - specify a template to be interpolated
  :values           - values for interpolation
  :blob             - map of :container, :path
  :blobstore        - a jclouds blobstore object (override blobstore in request)
  :overwrite-changes - flag to force overwriting of locally modified content
  :no-versioning    - do not version the file
  :max-versions     - specfy the number of versions to keep (default 5)
Options for specifying the file's permissions are:
  :owner user-name
  :group group-name
  :mode  file-mode"
  (remote-file*
   [request path & {:keys [action url local-file remote-file link
                           content literal
                           template values
                           md5 md5-url
                           owner group mode force
                           blob blobstore
                           overwrite-changes no-versioning max-versions]
                    :or {action :create max-versions 5}
                    :as options}]
   (let [new-path (str path ".new")
         md5-path (str path ".md5")
         versioning (if no-versioning "" (stevedore/script (backup-option)))]
     (case action
       :create
       (stevedore/checked-commands
        (str "remote-file " path)
        (cond
         (and url md5) (stevedore/chained-script
                        (if (|| (not (file-exists? ~path))
                                (!= ~md5 @((pipe
                                            (md5sum ~path)
                                            (cut "-f1" "-d" "' '")))))
                          ~(stevedore/chained-script
                            (download-file ~url ~new-path))))
         ;; Download md5 to temporary directory.
         (and url md5-url) (stevedore/chained-script
                            (var tmpdir (quoted (make-temp-dir "rf")))
                            (var basefile (quoted (str @tmpdir "/" @(basename ~path))))
                            (var newmd5path (quoted (str @basefile ".md5")))
                            (download-file ~md5-url @newmd5path)
                            (if (|| (not (file-exists? ~md5-path))
                                    (diff @newmd5path ~md5-path))
                              (do
                                (download-file ~url ~new-path)
                                (ln -s ~new-path @basefile)
                                (if-not (md5sum
                                         @newmd5path :quiet true :check true)
                                  (do
                                    (echo ~(str "Download of " url
                                                " failed to match md5"))
                                    (exit 1)))))
                            (rm @tmpdir ~{:force true :recursive true}))
         url (stevedore/chained-script
              (download-file ~url ~new-path))
         content (apply file/heredoc
                        new-path content
                        (apply concat (seq (select-keys options [:literal]))))
         local-file (let [temp-path (resource/register-file-transfer!
                                     local-file)]
                      (stevedore/script
                       (mv -f (str "~/" ~temp-path) ~new-path)))
         remote-file (stevedore/script
                      (cp -f ~remote-file ~new-path))
         template (apply
                   file/heredoc
                   new-path
                   (templates/interpolate-template
                    template (or values {}) (:node-type request))
                   (apply concat (seq (select-keys options [:literal]))))
         link (stevedore/script (ln -f -s ~link ~path))
         blob (stevedore/checked-script
               "Download blob"
               (download-request
                ~new-path
                ~(jclouds-blobstore/sign-blob-request
                  (:container blob) (:path blob)
                  {:method :get}
                  (or blobstore (:blobstore request)))))
         :else (throw
                (IllegalArgumentException.
                 (str "remote-file " path " specified without content."))))

        ;; process the new file accordingly
        (when install-new-files
          (stevedore/chain-commands
           (if (or overwrite-changes no-versioning)
             (stevedore/script
              (if (file-exists? ~new-path)
                (mv -f ~versioning ~new-path ~path)))
             (stevedore/script
              (var md5diff "")
              (if (&& (file-exists? ~path) (file-exists? ~md5-path))
                (do
                  (md5sum ~md5-path :quiet true :check true)
                  (set! md5diff "$?")))
              (var contentdiff "")
              (if (&& (file-exists? ~path) (file-exists? ~new-path))
                (do
                  (diff -u ~path ~new-path)
                  (set! contentdiff "$?")))
              (if (== @md5diff 1)
                (do
                  (echo "Existing content did not match md5:")
                  (exit 1)))
              (if (!= @contentdiff "")
                (mv -f ~versioning ~new-path ~path))
              (if-not (file-exists? ~path)
                (mv ~new-path ~path))))
           (file/adjust-file path options)
           (file/write-md5-for-file path md5-path)
           (stevedore/script (echo "MD5 sum is" @(cat ~md5-path)))))
        ;; cleanup
        (if (and (not no-versioning) (pos? max-versions))
          (stevedore/script
           (pipe
            (ls -t (str ~path ".~[0-9]*~"))
            (tail -n ~(str "+" (inc max-versions)))
            (xargs rm -f)))))
       :delete (stevedore/checked-script
                (str "delete remote-file " path)
                (rm ~path ~(select-keys options [:force])))))))
