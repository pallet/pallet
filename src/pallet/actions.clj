(ns pallet.actions
  "Pallet's action primitives."
  (:require
   [clojure.java.io :as io]
   [clojure.set :refer [intersection]]
   [clojure.string :as string :refer [trim]]
   [clojure.tools.logging :as logging]
   [pallet.action :refer [defaction]]
   [pallet.action-options
    :refer [action-options with-action-options]]
   [pallet.actions.decl :as decl]
   [pallet.actions.impl :refer :all]
   [pallet.context :as context]
   [pallet.core.file-upload :refer :all]
   [pallet.environment :refer [get-environment]]
   [pallet.plan :refer [defplan plan-context]]
   [pallet.script.lib :as lib :refer [set-flag-value user-home]]
   [pallet.session :refer [target target-session?]]
   [pallet.target :as target :refer [node primary-ip ssh-port]]
   [pallet.target-info :refer [admin-user packager]]
   [pallet.stevedore :as stevedore :refer [fragment with-source-line-comments]]
   [pallet.utils :refer [apply-map log-multiline maybe-assoc tmpfile]]
   [useful.ns :refer [defalias]]
   [schema.core :as schema :refer [check required-key optional-key validate]]))

(defalias exec decl/exec)
(defalias exec-script* decl/exec-script*)

(defmacro exec-script
  "Execute a bash script remotely. The script is expressed in stevedore."
  [session & script]
  `(exec-script* ~session (stevedore/script ~@script)))

(defmacro ^{:requires [#'checked-script]}
  exec-checked-script
  "Execute a bash script remotely, throwing if any element of the
   script fails. The script is expressed in stevedore."
  [session script-name & script]
  (let [file (.getName (io/file *file*))
        line (:line (meta &form))]
    `(exec-script*
      ~session
      (checked-script
       (str ~script-name
            (if *script-location-info*
              ~(str " (" file ":" line ")")))
       ~@script))))

;;; # Flow Control
(defn ^:internal plan-flag-kw
  "Generator for plan flag keywords"
  []
  (keyword (name (gensym "flag"))))

;;; # Simple File Management
(defn file
  "Touch or remove a file. Can also set owner and permissions.

     - :action    one of :create, :delete, :touch
     - :owner     user name or id for owner of file
     - :group     user name or id for group of file
     - :mode      file permissions
     - :force     when deleting, try and force removal"
  ([session path {:keys [action owner group mode force]
                  :or {action :create force true}
                  :as options}]
     {:pre [(target-session? session)]}
     (decl/file session path (merge {:action :create} options)))
  ([session path]
     (file session path {})))

(defn symbolic-link
  "Symbolic link management.

     - :action    one of :create, :delete
     - :owner     user name or id for owner of symlink
     - :group     user name or id for group of symlink
     - :mode      symlink permissions
     - :force     when deleting, try and force removal
     - :no-deref  do not deref target if it is a symlink to a directory"
  ([session from name {:keys [action owner group mode force no-deref]
                       :or {action :create force true}
                       :as options}]
     {:pre [(target-session? session)]}
     (decl/symbolic-link session from name
                         (merge {action :create force true} options)))
  ([session from name]
     (decl/symbolic-link session from name {})))

(defn fifo
  "FIFO pipe management.

     - :action    one of :create, :delete
     - :owner     user name or id for owner of fifo
     - :group     user name or id for group of fifo
     - :mode      fifo permissions
     - :force     when deleting, try and force removal"
  ([session path {:keys [action owner group mode force]
                  :or {action :create}
                  :as options}]
     (decl/fifo session path (merge {action :create} options))))

(defn sed
  "Execute sed on the file at path.  Takes a map of expr to replacement.
     - :separator     the separator to use in generated sed expressions. This
                      will be inferred if not specified.
     - :no-md5        prevent md5 generation for the modified file
     - :restriction   restrict the sed expressions to a particular context."
  ([session path exprs-map {:keys [separator no-md5 restriction] :as options}]
     {:pre [(target-session? session)]}
     (decl/sed session path exprs-map options))
  ([session path exprs-map]
     (sed session path exprs-map {})))

;;; # Simple Directory Management
(defn directory
  "Directory management.

   For :create and :touch, all components of path are effected.

   Options are:
    - :action     One of :create, :touch, :delete
    - :recursive  Flag for recursive delete
    - :force      Flag for forced delete
    - :path       flag to create all path elements
    - :owner      set owner
    - :group      set group
    - :mode       set mode"
  ([session dir-path {:keys [action recursive force path mode verbose owner
                             group]
                      :or {action :create recursive true force true path true}
                      :as options}]
     {:pre [(target-session? session)]}
     (decl/directory
      session dir-path
      (merge {:action :create :recursive true :force true :path true} options)))
  ([session dir-path]
     (directory session dir-path {})))

(defn directories
  "Directory management of multiple directories with the same
   owner/group/permissions.

   `options` are as for `directory` and are applied to each directory in
   `paths`"
  [session paths options]
  (doseq [path paths]
    (directory session path options)))

;;; # Remote File Content

;;; `remote-file` has many options for the content of remote files.  Ownership
;;; and mode can of course be specified. By default the remote file is
;;; versioned, and multiple versions are kept.

;;; Modification of remote files outside of pallet cause an error to be raised
;;; by default.

(def
  ^{:doc "A vector of the options accepted by remote-file.  Can be used for
          option forwarding when calling remote-file from other crates."}
  content-options
  [:local-file :remote-file :url :md5 :content :literal :template :values
   :action :blob :blobstore :insecure :link])

(def
  ^{:doc "A vector of options for controlling versions. Can be used for option
          forwarding when calling remote-file from other crates."}
  version-options
  [:overwrite-changes :no-versioning :max-versions :flag-on-changed])

(def
  ^{:doc "A vector of options for controlling ownership. Can be used for option
          forwarding when calling remote-file from other crates."}
  ownership-options
  [:owner :group :mode])

(def
  ^{:doc "A vector of the options accepted by remote-file.  Can be used for
          option forwarding when calling remote-file from other crates."}
  all-options
  (concat content-options version-options ownership-options [:verify]))

(def remote-file-arguments
  (schema/both
   (schema/pred
    (fn [m] (some (set content-options) (keys m)))
    "Must have content")
   {(optional-key :local-file) String
    (optional-key :remote-file) String
    (optional-key :url) String
    (optional-key :md5) String
    (optional-key :md5-url) String
    (optional-key :content) String
    (optional-key :literal) schema/Any
    (optional-key :action) schema/Keyword
    (optional-key :blob) {:container String :path String}
    (optional-key :blobstore) schema/Any  ; cheating to avoid adding a reqiure
    (optional-key :insecure) schema/Any
    (optional-key :overwrite-changes) schema/Any
    (optional-key :no-versioning) schema/Any
    (optional-key :max-versions) Number
    (optional-key :flag-on-changed) String
    (optional-key :owner) String
    (optional-key :group) String
    (optional-key :mode) (schema/either String schema/Int)
    (optional-key :force) schema/Any
    (optional-key :link) String
    (optional-key :verify) schema/Any}))

(defn check-remote-file-arguments
  [m]
  (validate remote-file-arguments m))

(def remote-directory-arguments
  (schema/both
   (schema/pred
    (fn [m] (some (set content-options) (keys m)))
    "Must have content")
   {(optional-key :local-file) String
    (optional-key :remote-file) String
    (optional-key :url) String
    (optional-key :md5) String
    (optional-key :md5-url) String
    (optional-key :action) schema/Keyword
    (optional-key :blob) {:container String :path String}
    (optional-key :blobstore) schema/Any ; cheating to avoid adding a reqiure
    (optional-key :overwrite-changes) schema/Any
    (optional-key :owner) String
    (optional-key :group) String
    (optional-key :recursive) schema/Any
    (optional-key :unpack) schema/Any
    (optional-key :extract-files) [String]
    (optional-key :mode) (schema/either String schema/Int)
    (optional-key :tar-options) String
    (optional-key :unzip-options) String
    (optional-key :strip-components) Number
    (optional-key :install-new-files) schema/Any}))

(defn check-remote-directory-arguments
  [m]
  (validate remote-directory-arguments m))

(defn set-install-new-files
  "Set boolean flag to control installation of new files"
  [flag]
  (alter-var-root #'*install-new-files* (fn [_] flag)))

(defn set-force-overwrite
  "Globally force installation of new files, even if content on node has
  changed."
  [flag]
  (alter-var-root #'*force-overwrite* (fn [_] flag)))

(defn remote-file
  "Remote file content management.

The `remote-file` action can specify the content of a remote file in a number
different ways.

By default, the remote-file is versioned, and 5 versions are kept.

The remote content is also verified against its md5 hash.  If the contents
of the remote file have changed (e.g. have been edited on the remote machine)
then by default the file will not be overwritten, and an error will be raised.
To force overwrite, call `set-force-overwrite` before running `converge` or
`lift`.

Options for specifying the file's content are:
`url`
: download the specified url to the given filepath

`content`
: use the specified content directly

`local-file`
: use the file on the local machine at the given path

`remote-file`
: use the file on the remote machine at the given path

`link`
: file to link to

`literal`
: prevent shell expansion on content

`md5`
: md5 for file

`md5-url`
: a url containing file's md5

`template`
: specify a template to be interpolated

`values`
: values for interpolation

`blob`
: map of `container`, `path`

`blobstore`
: a jclouds blobstore object (override blobstore in session)

`insecure`
: boolean to specify ignoring of SLL certs

Options for version control are:

`overwrite-changes`
: flag to force overwriting of locally modified content

`no-versioning`
: do not version the file

`max-versions`
: specify the number of versions to keep (default 5)

`flag-on-changed`
: flag to set if file is changed

Options for specifying the file's permissions are:

`owner`
: user-name

`group`
: group-name

`mode`
: file-mode

Options for verifying the file's content:

`verify`
: a command to run on the file on the node, before it is installed

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
  [session path {:keys [action url local-file remote-file link
                        content literal
                        template values
                        md5 md5-url
                        owner group mode force
                        blob blobstore
                        install-new-files
                        overwrite-changes no-versioning max-versions
                        flag-on-changed
                        local-file-options
                        verify]
                 :as options}]
  {:pre [path (target-session? session)]}
  (check-remote-file-arguments options)
  (verify-local-file-exists local-file)
  (let [action-options (action-options session)
        script-dir (:script-dir action-options)
        user (if (= :sudo (:script-prefix action-options :sudo))
               (:sudo-user action-options)
               (:username (admin-user session)))]
    (when local-file
      (decl/transfer-file session local-file path))
    (let [{:keys [exit] :as result}
          (decl/remote-file
           session
           path
           (merge
            (maybe-assoc
             {:install-new-files *install-new-files* ; capture bound values
              :overwrite-changes *force-overwrite*
              :owner user
              :proxy (get-environment session [:proxy] nil)}
             :blobstore (get-environment session [:blobstore] nil))
            options))]
      (when (= 2 exit)
        (throw (ex-info "Local file failed to transfer" {})))
      result)))

(defn with-remote-file
  "Function to call f with a local copy of the sessioned remote path.
   f should be a function taking [session local-path & _], where local-path will
   be a File with a copy of the remote file (which will be unlinked after
   calling f."
  [session f path & args]
  (let [local-path (tmpfile)]
    (plan-context with-remote-file-fn {:local-path local-path}
      (decl/transfer-file-to-local session path local-path)
      (apply f local-path args)
      (.delete (io/file local-path)))))

(defn remote-file-content
  "Return a function that returns the content of a file, when used inside
   another action."
  [session path]
  {:pre [(target-session? session)]}
  (let [nv (exec-script session (~lib/cat ~path))]
    (:out nv)))

;;; # Remote Directory Content

(defn remote-directory
  "Specify the contents of remote directory.

Options:

`:url`
: a url to download content from

`:unpack`
: how download should be extracts (default :tar)

`:tar-options`
: options to pass to tar (default \"xz\")

`:unzip-options`
: options to pass to unzip (default \"-o\")

`:jar-options`
: options to pass to unzip (default \"xf\") jar does not support stripping path
  components

`:strip-components`
: number of path components to remove when unpacking

`:extract-files`
: extract only the specified files or directories from the archive

`:md5`
: md5 of file to unpack

`:md5-url`
: url of md5 file for file to unpack

Ownership options:
`:owner`
: owner of files

`:group`
: group of files

`:recursive`
: flag to recursively set owner and group

To install the content of an url pointing at a tar file, specify the :url
option.

    (remote-directory session path
       :url \"http://a.com/path/file.tgz\")

If there is an md5 url with the tar file's md5, you can specify that as well,
to prevent unnecessary downloads and verify the content.

    (remote-directory session path
       :url \"http://a.com/path/file.tgz\"
       :md5-url \"http://a.com/path/file.md5\")

To install the content of an url pointing at a zip file, specify the :url
option and :unpack :unzip.

    (remote-directory session path
       :url \"http://a.com/path/file.\"
       :unpack :unzip)

To install the content of an url pointing at a jar/tar/zip file, extracting
only specified files or directories, use the :extract-files option.

    (remote-directory session path
       :url \"http://a.com/path/file.jar\"
       :unpack :jar
       :extract-files [\"dir/file\" \"file2\"])"
  [session path {:keys [action url local-file remote-file
                        unpack tar-options unzip-options jar-options
                        strip-components md5 md5-url owner group recursive
                        force-overwrite
                        local-file-options]
                 :or {action :create
                      tar-options "xz"
                      unzip-options "-o"
                      jar-options "xf"
                      strip-components 1
                      recursive true}
                 :as options}]
  {:pre [(target-session? session)]}
  (check-remote-directory-arguments options)
  (verify-local-file-exists local-file)
  (let [action-options (action-options session)
        script-dir (:script-dir action-options)
        user (if (= :sudo (:script-prefix action-options :sudo))
               (:sudo-user action-options)
               (:username (admin-user session)))]
    (when local-file
      (decl/transfer-file local-file path))
    (directory session path {:owner owner :group group :recursive false})
    (decl/remote-directory
     session
     path
     (merge
      {:install-new-files *install-new-files* ; capture bound values
       :overwrite-changes *force-overwrite*
       :owner user
       :blobstore (get-environment session [:blobstore] nil)
       :proxy (get-environment session [:proxy] nil)}
      options))))

(defn wait-for-file
  "Wait for a file to exist"
  ([session path {:keys [max-retries standoff service-name]
                  :or {action :create max-versions 5
                       install-new-files true}
                  :as options}]
     (decl/wait-for-file session path
                         (merge {:action :create
                                 :max-versions 5
                                 :install-new-files true}
                                options)))
  ([session path]
     (wait-for-file session path {})))


;;; # Packages
(defalias package-source-changed-flag decl/package-source-changed-flag)

(defplan packages
  "Install packages with common options.

   Options
    - :action [:install | :remove | :upgrade]
    - :purge [true|false]         when removing, whether to remove all config
    - :enable [repo|(seq repo)]   enable specific repository
    - :disable [repo|(seq repo)]  disable specific repository
    - :priority n                 priority (0-100, default 50)
    - :disable-service-start      disable service startup (default false)
    - :allow-unsigned             install package even if unsigned

       (packages session [\"git\" \"git-email\"])
       (packages session [\"git-core\" \"git-email\"] {:action :remove})"
  ([session package-names {:keys [yum aptitude pacman brew] :as options}]
     (let [packager (packager session)]
       (decl/packages session package-names options)))
  ([session package-names]
     (packages session package-names {})))

(defn package
  "Install or remove a package.

   Options
    - :action [:install | :remove | :upgrade]
    - :purge [true|false]         when removing, whether to remove all config
    - :enable [repo|(seq repo)]   enable specific repository
    - :disable [repo|(seq repo)]  disable specific repository
    - :priority n                 priority (0-100, default 50)
    - :disable-service-start      disable service startup (default false)
    - :allow-unsigned             install package even if unsigned

   For package management to occur in one shot, use pallet.crate.package."
  ([session package-name {:keys [action y force purge enable disable priority]
                          :or {action :install
                               y true
                               priority 50}
                          :as options}]
     (decl/package session package-name
                   (merge {:packager (packager session)} options)))
  ([session package-name] (package session package-name {})))

(defplan package-manager
  "Package manager controls.

   `action` is one of the following:
   - :update          - update the list of available packages
   - :list-installed  - output a list of the installed packages
   - :add-scope       - enable a scope (eg. multiverse, non-free)

   To refresh the list of packages known to the package manager:
       (package-manager session :update)

   To enable multiverse on ubuntu:
       (package-manager session :add-scope :scope :multiverse)

   To enable non-free on debian:
       (package-manager session :add-scope :scope :non-free)"
  ([session action {:keys [packages scope] :as options}]
     (decl/package-manager
      session action (merge {:packager (packager session)} options)))
  ([session action]
     (package-manager session action {})))

(defn package-source
  "Control package sources.
   Options are the package manager keywords, each specifying a map of
   packager specific options.

## `:aptitude`

`:source-type source-string`
: the source type (default \"deb\")

`:url url-string`
: the repository url

`:scopes seq`
: scopes to enable for repository

`:release release-name`
: override the release name

`:key-url url-string`
: url for key

`:key-server hostname`
: hostname to use as a keyserver

`:key-id id`
: id for key to look it up from keyserver

## `:yum`

`:name name`
: repository name

`:url url-string`
: repository base url

`:gpgkey url-string`
: gpg key url for repository

## Example

    (package-source \"Partner\"
      :aptitude {:url \"http://archive.canonical.com/\"
                 :scopes [\"partner\"]})"
  {:always-before #{package-manager package}
   :execution :aggregated}
  [session name {:keys [] :as options}]
  (decl/package-source session name
                       (merge {:packager (packager session)})))

(defn add-rpm
  "Add an rpm.  Source options are as for remote file."
  [session rpm-name {:as options}]
  (decl/add-rpm session rpm-name options))

(defn install-deb
  "Add a deb.  Source options are as for remote file."
  [session deb-name {:as options}]
  (decl/install-deb session deb-name options))

(defn debconf-set-selections
  "Set debconf selections.
Specify `:line` as a string, or `:package`, `:question`, `:type` and
`:value` options."
  [session {:keys [line package question type value] :as options}]
  (decl/debconf-set-selections session options))

(defmulti repository
  "Install the specified repository as a package source.
The :id key must contain a recognised repository."
  (fn [session {:keys [id]}]
    id))

;;; # Synch local file to remote
(defn rsync
  "Use rsync to copy files from local-path to remote-path"
  [session local-path remote-path {:keys [port]
                                   :as options}]
  (decl/rsync session local-path remote-path
              (merge {:port (ssh-port (target session))
                      :username (:username (admin-user session))
                      :ip (primary-ip (target session))}
                     options)))

(defn rsync-to-local
  "Use rsync to copy files from remote-path to local-path"
  [session remote-path local-path {:keys [port ip username] :as options}]
  (decl/rsync-to-local session local-path remote-path
                       (merge {:port (ssh-port (target session))
                               :username (:username (admin-user session))
                               :ip (primary-ip (target session))}
                              options)))

(defn rsync-directory
  "Rsync from a local directory to a remote directory."
  [session from to & {:keys [owner group mode port] :as options}]
  (plan-context rsync-directory-fn {:name :rsync-directory}
    ;; would like to ensure rsync is installed, but this requires
    ;; root permissions, and doesn't work when this is run without
    ;; root permision
    ;; (package "rsync")
    (directory session to {:owner owner :group group :mode mode})
    (rsync session from to options)))

(defn rsync-to-local-directory
  "Rsync from a local directory to a remote directory."
  [session from to & {:keys [owner group mode port] :as options}]
  (plan-context rsync-directory-fn {:name :rsync-directory}
    (rsync-to-local session from to options)))

;;; # Users and Groups
(defn group
  "User Group Management.

`:action`
: One of :create, :manage, :remove.

`:gid`
: specify the group id

`:password`
: An MD5 crypted password for the user.

`:system`
: Specify the user as a system user."
  ([session groupname {:keys [action system gid password]
                       :or {action :manage}
                       :as options}]
     (decl/group session groupname (merge {action :manage} options)))
  ([session groupname]
     (group session groupname {})))

(defn user
  "User management.

`:action`
: One of :create, :manage, :lock, :unlock or :remove.

`:shell`
: One of :bash, :csh, :ksh, :rsh, :sh, :tcsh, :zsh, :false or a path string.

`:create-home`
: Controls creation of the user's home directory.

`:base-dir`
: The directory in which default user directories should be created.

`:home`
: Specify the user's home directory.

`:system`
: Specify the user as a system user.

`:comment`
: A comment to record for the user.

`:group`
: The user's login group. Defaults to a group with the same name as the user.

`:groups`
: Additional groups the user should belong to.

`:password`
: An MD5 crypted password for the user.

`:force`
: Force user removal."
  ([session username
    {:keys [action shell base-dir home system create-home
            password shell comment group groups remove force append]
     :or {action :manage}
     :as options}]
     (decl/user session username (merge {:action :manage} options)))
  ([session username]
     (user session username {})))

;;; # Services
(defn service
  "Control services.

   - :action  accepts either startstop, restart, enable or disable keywords.
   - :if-flag  makes start, stop, and restart conditional on the specified flag
               as set, for example, by remote-file :flag-on-changed
   - :sequence-start  a sequence of [sequence-number level level ...], where
                      sequence number determines the order in which services
                      are started within a level.
   - :service-impl    either :initd or :upstart

Deprecated in favour of pallet.crate.service/service."
  ([session service-name {:keys [action if-flag if-stopped service-impl]
                          :or {action :start service-impl :initd}
                          :as options}]
     (decl/service session service-name
                   (merge {:action :start :service-impl :initd} options)))
  ([session service-name]
     (service session service-name {})))

(defmacro with-service-restart
  "Stop the given service, execute the body, and then restart."
  [session service-name & body]
  `(let [session# session
         service# ~service-name]
     (plan-context with-restart {:service service#}
       (service session#  service# :action :stop)
       ~@body
       (service session# service# :action :start))))

(defn service-script
  "Install a service script.  Sources as for remote-file."
  [session service-name & {:keys [action url local-file remote-file link
                          content literal template values md5 md5-url
                          force service-impl]
                   :or {action :create service-impl :initd}
                   :as options}]
  (plan-context init-script {}
    (apply-map
     pallet.actions/remote-file
     session
     (service-script-path service-impl service-name)
     :owner "root" :group "root" :mode "0755"
     (merge {:action action} options))))

;;; # Retry
(defn loop-until
  {:no-doc true}
  [session service-name condition max-retries standoff]
  (exec-checked-script
   session
   (format "Wait for %s" service-name)
   (group (chain-or (let x 0) true))
   (while (not ~condition)
     (do
       (let x (+ x 1))
       (if (= ~max-retries @x)
         (do
           (println
            ~(format "Timed out waiting for %s" service-name)
            >&2)
           (~lib/exit 1)))
       (println ~(format "Waiting for %s" service-name))
       ("sleep" ~standoff)))))

(defmacro retry-until
  "Repeat an action until it succeeds"
  [session {:keys [max-retries standoff service-name]
            :or {max-retries 5 standoff 2}}
   condition]
  (let [service-name (or service-name "retryable")]
    `(loop-until ~session ~service-name ~condition ~max-retries ~standoff)))

;;; target filters

(defn ^:internal one-node-filter
  [role->nodes [role & roles]]
  (let [role-nodes (set (role->nodes role))
        m (select-keys role->nodes roles)]
    (or (first (reduce
                (fn [result [role nodes]]
                  (intersection result (set nodes)))
                role-nodes
                m))
        (first role-nodes))))

(defmacro on-one-node
  "Execute the body on just one node of the specified roles. If there is no
   node in the union of nodes for all the roles, the nodes for the first role
   are used."
  [session roles & body]
  `(let [target# (target)
         role->nodes# (role->nodes-map)]
     (when
         (= target# (one-node-filter role->nodes# ~roles))
       ~@body)))

(defmacro log-script-output
  "Log the result of a script action."
  ;; This is a macro so that logging occurs in the caller's namespace
  [script-return-value
   {:keys [out err exit fmt]
    :or {out :debug err :info exit :none fmt "%s"}}]
  `(with-action-values
    [~script-return-value]
    (when (and (not= ~out :none) (:out ~script-return-value))
      (log-multiline ~out ~fmt (trim (:out ~script-return-value))))
    (when (and (not= ~err :none) (:err ~script-return-value))
      (log-multiline ~err ~fmt (trim (:err ~script-return-value))))
    (when (not= ~exit :none)
      (log-multiline ~exit ~fmt (:exit ~script-return-value)))))
