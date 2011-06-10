(ns pallet.action.remote-file
  "Action to specify remote file content.

   `remote-file` has many options for the content of remote files.  Ownership
   and mode can of course be specified. By default the remote file is versioned,
   and multiple versions are kept.

   Modification of remote files outside of pallet cause an error to be raised
   by default."
  (:require
   [pallet.action :as action]
   [pallet.action.directory :as directory]
   [pallet.action.file :as file]
   [pallet.blobstore :as blobstore]
   [pallet.environment :as environment]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.template :as templates]
   [pallet.utils :as utils]
   [clojure.contrib.def :as def]
   [clojure.java.io :as io])
  (:use
   pallet.thread-expr))

(def install-new-files true)
(def force-overwrite false)

(defn set-install-new-files
  "Set boolean flag to control installation of new files"
  [flag]
  (alter-var-root #'install-new-files (fn [_] flag)))

(defn set-force-overwrite
  "Globally force installation of new files, even if content on node has
  changed."
  [flag]
  (alter-var-root #'force-overwrite (fn [_] flag)))

(def/defvar
  content-options
  [:local-file :remote-file :url :md5 :content :literal :template :values
   :action :blob :blobstore]
  "A vector of the options accepted by remote-file.  Can be used for option
  forwarding when calling remote-file from other crates.")

(def/defvar
  version-options
  [:overwrite-changes :no-versioning :max-versions :flag-on-changed]
  "A vector of options for controlling versions. Can be used for option
  forwarding when calling remote-file from other crates.")

(def/defvar
  ownership-options
  [:owner :group :mode]
  "A vector of options for controlling ownership. Can be used for option
  forwarding when calling remote-file from other crates.")

(def/defvar
  all-options
  (concat content-options version-options ownership-options)
  "A vector of the options accepted by remote-file.  Can be used for option
  forwarding when calling remote-file from other crates.")

(defn- get-session
  "Build a curl or wget command from the specified session object."
  [session]
  (stevedore/script
   (if (test @(~lib/which curl))
     (curl -s "--retry" 20
           ~(apply str (map
                        #(format "-H \"%s: %s\" " (first %) (second %))
                        (.. session getHeaders entries)))
           ~(.. session getEndpoint toASCIIString))
     (if (test @(~lib/which wget))
       (wget -nv "--tries" 20
             ~(apply str (map
                          #(format "--header \"%s: %s\" " (first %) (second %))
                          (.. session getHeaders entries)))
             ~(.. session getEndpoint toASCIIString))
       (do
         (println "No download utility available")
         (~lib/exit 1))))))

(defn- arg-vector
  "Return the non-session arguments."
  [_ & args]
  args)

(defn- delete-local-path
  [session local-path]
  (.delete local-path)
  session)

(defn with-remote-file
  "Function to call f with a local copy of the sessioned remote path.
   f should be a function taking [session local-path & _], where local-path will
   be a File with a copy of the remote file (which will be unlinked after
   calling f."
  [session f path & args]
  (let [local-path (utils/tmpfile)]
    (->
     session
     (action/schedule-action
      arg-vector
      {}
      [path (.getPath local-path)]
      :in-sequence :transfer/to-local :origin)
     (apply-> f local-path args)
     (action/schedule-action
      delete-local-path
      {}
      [local-path]
      :in-sequence :fn/clojure :origin))))

(defn transfer-file
  "Function to transfer a local file."
  [session local-path remote-path {:as options}]
  (action/schedule-action
   session
   arg-vector
   options
   [local-path remote-path]
   :in-sequence :transfer/from-local :origin))

(action/def-bash-action remote-file-action
  [session path & {:keys [action url local-file remote-file link
                          content literal
                          template values
                          md5 md5-url
                          owner group mode force
                          blob blobstore
                          overwrite-changes no-versioning max-versions
                          flag-on-changed
                          force]
                   :or {action :create max-versions 5}
                   :as options}]
  (let [new-path (str path ".new")
        md5-path (str path ".md5")
        versioning (if no-versioning nil :numbered)
        proxy (environment/get-for session [:proxy] nil)]
    (case action
      :create
      (stevedore/checked-commands
       (str "remote-file " path)
       (cond
        (and url md5) (stevedore/chained-script
                       (if (|| (not (file-exists? ~path))
                               (!= ~md5 @((pipe
                                           (~lib/md5sum ~path)
                                           (~lib/cut
                                            "" :fields 1 :delimiter " ")))))
                         ~(stevedore/chained-script
                           (~lib/download-file ~url ~new-path :proxy ~proxy))))
        ;; Download md5 to temporary directory.
        (and url md5-url) (stevedore/chained-script
                           (var tmpdir (quoted (~lib/make-temp-dir "rf")))
                           (var basefile
                                (quoted
                                 (str @tmpdir "/" @(~lib/basename ~path))))
                           (var newmd5path (quoted (str @basefile ".md5")))
                           (~lib/download-file
                            ~md5-url @newmd5path :proxy ~proxy)
                           (~lib/normalise-md5 @newmd5path)
                           (if (|| (not (file-exists? ~md5-path))
                                   (~lib/diff @newmd5path ~md5-path))
                             (do
                               (~lib/download-file ~url ~new-path :proxy ~proxy)
                               (~lib/ln ~new-path @basefile)
                               (if-not (~lib/md5sum-verify @newmd5path)
                                 (do
                                   (println ~(str "Download of " url
                                                  " failed to match md5"))
                                   (~lib/exit 1)))))
                           (~lib/rm @tmpdir :force ~true :recursive ~true))
        url (stevedore/chained-script
             (~lib/download-file ~url ~new-path :proxy ~proxy))
        content (stevedore/script
                 (~lib/heredoc
                  ~new-path ~content ~(select-keys options [:literal])))
        local-file nil
        ;; (let [temp-path (action/register-file-transfer!
        ;;                   local-file)]
        ;;    (stevedore/script
        ;;     (mv -f (str "~/" ~temp-path) ~new-path)))
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
        blob (stevedore/checked-script
              "Download blob"
              (download-session
               ~new-path
               ~(blobstore/sign-blob-request
                 (or blobstore (environment/get-for session [:blobstore] nil)
                     (throw (IllegalArgumentException.
                             "No :blobstore given for blob content.") ))
                 (:container blob) (:path blob)
                 {:method :get})))
        :else (throw
               (IllegalArgumentException.
                (str "remote-file " path " specified without content."))))

       ;; process the new file accordingly
       (when install-new-files
         (stevedore/chain-commands
          (if (or overwrite-changes no-versioning force-overwrite)
            (stevedore/script
             (if (file-exists? ~new-path)
               (do
                 ~(stevedore/chain-commands
                   (stevedore/script
                    (~lib/mv ~new-path ~path :backup ~versioning :force ~true))
                   (if flag-on-changed
                     (stevedore/script (~lib/set-flag ~flag-on-changed)))))))
            (stevedore/script
             (var md5diff "")
             (if (&& (file-exists? ~path) (file-exists? ~md5-path))
               (do
                 (~lib/md5sum-verify ~md5-path)
                 (set! md5diff "$?")))
             (var contentdiff "")
             (if (&& (file-exists? ~path) (file-exists? ~new-path))
               (do
                 (~lib/diff ~path ~new-path :unified true)
                 (set! contentdiff "$?")))
             (if (== @md5diff 1)
               (do
                 (println "Existing content did not match md5:")
                 (~lib/exit 1)))
             (if (!= @contentdiff "0")
               (do
                 ~(stevedore/chain-commands
                   (stevedore/script
                    (~lib/mv ~new-path ~path :force ~true :backup ~versioning))
                   (if flag-on-changed
                     (stevedore/script (~lib/set-flag ~flag-on-changed))))))
             (if-not (file-exists? ~path)
               (do
                 ~(stevedore/chain-commands
                   (stevedore/script (~lib/mv ~new-path ~path))
                   (if flag-on-changed
                     (stevedore/script (~lib/set-flag ~flag-on-changed))))))))
          (file/adjust-file path options)
          (when-not no-versioning
            (stevedore/chain-commands
             (file/write-md5-for-file path md5-path)
             (stevedore/script
              (println "MD5 sum is" @(~lib/cat ~md5-path)))))))
       ;; cleanup
       (if (and (not no-versioning) (pos? max-versions))
         (stevedore/script
          (pipe
           ((~lib/ls (str ~path ".~[0-9]*~") :sort-by-time ~true)
            "2>" "/dev/null")
           (~lib/tail "" :max-lines ~(str "+" (inc max-versions)))
           (~lib/xargs (~lib/rm "" :force ~true))))))
      :delete (stevedore/checked-script
               (str "delete remote-file " path)
               (~lib/rm ~path :force ~force)))))

(defn remote-file
  "Remote file content management.

The `remote-file` action can specify the content of a remote file in a number
different ways.

By default, the remote-file is versioned, and 5 versions are kept.

The remote content is also verified against it's md5 hash.  If the contents
of the remote file have changed (e.g. have been edited on the remote machine)
then by default the file will not be overwritten, and an error will be raised.
To force overwrite, call `set-force-overwrite` before running `converge` or
`lift`.

Options for specifying the file's content are:
  :url url          - download the specified url to the given filepath
  :content string   - use the specified content directly
  :local-file path  - use the file on the local machine at the given path
  :remote-file path - use the file on the remote machine at the given path
  :link             - file to link to
  :literal          - prevent shell expansion on content
  :md5              - md5 for file
  :md5-url          - a url containing file's md5
  :template         - specify a template to be interpolated
  :values           - values for interpolation
  :blob             - map of :container, :path
  :blobstore        - a jclouds blobstore object (override blobstore in session)

Options for version control are:
  :overwrite-changes - flag to force overwriting of locally modified content
  :no-versioning    - do not version the file
  :max-versions     - specfy the number of versions to keep (default 5)
  :flag-on-changed  - flag to set if file is changed

Options for specifying the file's permissions are:
  :owner user-name
  :group group-name
  :mode  file-mode

To copy the content of a local file to a remote file:
    (remote-file session \"remote/path\" :local-file \"local/path\")

To copy the content of one remote file to another remote file:
    (remote-file session \"remote/path\" :remote-file \"remote/source/path\")

To link one remote file to another remote file:
    (remote-file session \"remote/path\" :link \"remote/source/path\")

To download a url to a remote file:
    (remote-file session \"remote/path\" :url \"http://a.com/path\")

If a url to a md5 file is also available, then it can be specified to prevent
unnecessary downloads and to verify the download.
    (remote-file session \"remote/path\"
      :url \"http://a.com/path\"
      :md5-url \"http://a.com/path.md5\")

If the md5 of the file to download, it can be specified to prevent unnecessary
downloads and to verify the download.
    (remote-file session \"remote/path\"
      :url \"http://a.com/path\"
      :md5 \"6de9439834c9147569741d3c9c9fc010\")

Content can also be copied from a blobstore.
    (remote-file session \"remote/path\"
      :blob {:container \"container\" :path \"blob\"})"
  [session path & {:keys [action url local-file remote-file link
                          content literal
                          template values
                          md5 md5-url
                          owner group mode force
                          blob blobstore
                          overwrite-changes no-versioning max-versions
                          flag-on-changed
                          local-file-options]
                   :as options}]
  (when-let [f (and local-file (io/file local-file))]
    (when (not (and (.exists f) (.isFile f) (.canRead f)))
      (throw (IllegalArgumentException.
              (format
               (str "'%s' does not exist, is a directory, or is unreadable; "
                    "cannot register it for transfer.")
               local-file)))))
  (->
   session
   (when-> local-file
           ;; transfer local file to remote system if required
           (transfer-file local-file (str path ".new") local-file-options))
   (action/with-precedence local-file-options
     (apply-map->
      remote-file-action path
      (merge
       {:overwrite-changes force-overwrite} ;; capture the value of the flag
       options)))))
