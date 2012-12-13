(ns pallet.actions
  "Pallet's action primitives."
  (:require
   [clojure.tools.logging :as logging]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore])
  (:use
   [clojure.set :only [intersection]]
   pallet.actions-impl
   [pallet.action
    :only [clj-action defaction with-action-options enter-scope leave-scope]]
   [pallet.argument :only [delayed]]
   [pallet.crate :only [role->nodes-map packager target]]
   [pallet.monad :only [let-s phase-pipeline phase-pipeline-no-context]]
   [pallet.monad.state-monad :only [m-when m-result]]
   [pallet.node-value :only [node-value]]
   [pallet.script.lib :only [set-flag-value]]
   [pallet.utils :only [apply-map tmpfile]]))

;;; # Direct Script Execution

;;; Sometimes pallet's other actions will not suffice for what you would like to
;;; achieve, so the exec-script actions allow you to execute arbitrary script.
(defaction exec
  "Execute script on the target node. The `script` is a plain string. `type`
   specifies the script language (default :bash). You can override the
   interpreter path using the `:interpreter` option."
  [{:keys [language interpreter version] :or {language :bash}} script])


(defaction exec-script*
  "Execute script on the target node. The script is a plain string."
  [script])

(defmacro exec-script
  "Execute a bash script remotely. The script is expressed in stevedore."
  [& script]
  `(exec-script* (stevedore/script ~@script)))

(defmacro exec-checked-script
  "Execute a bash script remotely, throwing if any element of the
   script fails. The script is expressed in stevedore."
  [script-name & script]
  `(exec-script* (checked-script ~script-name ~@script)))

;;; # Flow Control
(defmacro pipeline-when
  "Execute the crate-fns-or-actions, only when condition is true."
  {:indent 1}
  [condition & crate-fns-or-actions]
  (let [nv (gensym "nv")
        nv-kw (keyword (name nv))
        is-stevedore? (and (sequential? condition)
                           (symbol? (first condition))
                           (= (resolve (first condition)) #'stevedore/script))
        is-script? (or (string? condition) is-stevedore?)]
    `(phase-pipeline pipeline-when {:condition ~(list 'quote condition)}
       [~@(when is-script?
            [nv `(exec-checked-script
                  (str "Check " ~condition)
                  (~(list 'unquote `set-flag-value)
                   ~(name nv-kw)
                   @(do
                      ~@(if is-stevedore?
                          (rest condition)
                          ["test" condition])
                      (~'echo @~'?))))])]
       (if-action ~(if is-script?
                     ;; `(delayed [s#]
                     ;;    (= (-> (node-value ~nv s#) :flag-values ~nv-kw) "0"))
                     ;; `(delayed [~'&session] ~condition)
                     `(= (-> (deref ~nv) :flag-values ~nv-kw) "0")
                     condition))
       enter-scope
       ~@crate-fns-or-actions
       leave-scope)))

(defmacro pipeline-when-not
  "Execute the crate-fns-or-actions, only when condition is false."
  {:indent 1}
  [condition & crate-fns-or-actions]
  (let [nv (gensym "nv")
        nv-kw (keyword (name nv))
        is-stevedore? (and (sequential? condition)
                           (symbol? (first condition))
                           (= (resolve (first condition)) #'stevedore/script))
        is-script? (or (string? condition) is-stevedore?)]
    `(phase-pipeline pipeline-when-not {:condition ~(list 'quote condition)}
       [~@(when is-script?
            [nv `(exec-checked-script
                  (str "Check not " ~condition)
                  (~(list `unquote `set-flag-value)
                   ~(name nv-kw)
                   @(do
                      ~@(if is-stevedore?
                          (rest condition)
                          ["test" condition])
                      (~'echo @~'?))))])]
       (if-action ~(if is-script?
                     `(delayed [s#]
                        (= (-> (node-value ~nv s#) :flag-values ~nv-kw) "0"))
                     condition))
       enter-scope
       leave-scope
       enter-scope
       ~@crate-fns-or-actions
       leave-scope)))

(defmacro return-value-expr
  "Creates an action that can transform return values"
  [[& return-values] & body]
  (let [session (gensym "session")]
    `((clj-action [~session]
        [(let [~@(mapcat #(vector % `(node-value ~% ~session)) return-values)]
           (logging/debugf "return-value-expr %s" ~(vec return-values))
           ~@body)
         ~session]))))

(defaction assoc-settings
  "Set the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility (the default is
   :default)."
  [facility kv-pairs & {:keys [instance-id]}])

(defaction update-settings
  "Update the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility (the default is
   :default)."
  [facility options-or-f & args]
  {:arglists '[[facility options f & args] [facility f & args]]})

;;; # Simple File Management
(defaction file
  "Touch or remove a file. Can also set owner and permissions.

     - :action    one of :create, :delete, :touch
     - :owner     user name or id for owner of file
     - :group     user name or id for group of file
     - :mode      file permissions
     - :force     when deleting, try and force removal"
  [path & {:keys [action owner group mode force]
           :or {action :create force true}}])

(defaction symbolic-link
  "Symbolic link management.

     - :action    one of :create, :delete
     - :owner     user name or id for owner of symlink
     - :group     user name or id for group of symlink
     - :mode      symlink permissions
     - :force     when deleting, try and force removal"
  [from name & {:keys [action owner group mode force]
                :or {action :create force true}}])

(defaction fifo
  "FIFO pipe management.

     - :action    one of :create, :delete
     - :owner     user name or id for owner of fifo
     - :group     user name or id for group of fifo
     - :mode      fifo permissions
     - :force     when deleting, try and force removal"
  [path & {:keys [action owner group mode force]
           :or {action :create}}])

(defaction sed
  "Execute sed on the file at path.  Takes a map of expr to replacement.
     - :separator     the separator to use in generated sed expressions. This
                      will be inferred if not specified.
     - :no-md5        prevent md5 generation for the modified file
     - :restriction   restrict the sed expressions to a particular context."
  [path exprs-map & {:keys [separator no-md5 restriction]}])

;;; # Simple Directory Management
(defaction directory
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
  [dir-path & {:keys [action recursive force path mode verbose owner
                      group]
               :or {action :create recursive true force true path true}}])

(defaction directories
  "Directory management of multiple directories with the same
   owner/group/permissions.

   `options` are as for `directory` and are applied to each directory in
   `paths`"
  [paths & options])

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
   :action :blob :blobstore :insecure])

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
  (concat content-options version-options ownership-options))

(defaction transfer-file
  "Function to transfer a local file to a remote path."
  [local-path remote-path])

(defaction transfer-file-to-local
  "Function to transfer a remote file to a local path."
  [remote-path local-path])

(defaction delete-local-path
  "Function to delete a local path."
  [local-path])

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
  :insecure         - boolean to specify ignoring of SLL certs

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
  [path & {:keys [action url local-file remote-file link
                  content literal
                  template values
                  md5 md5-url
                  owner group mode force
                  blob blobstore
                  install-new-files
                  overwrite-changes no-versioning max-versions
                  flag-on-changed
                  local-file-options]
           :as options}]
  {:pre [path]}
  (verify-local-file-exists local-file)
  (let-s
    [_ (m-when local-file
         (transfer-file local-file (str path ".new")))
     f (with-action-options local-file-options
         (let-s
           [v (remote-file-action
               path
               (merge
                {:install-new-files *install-new-files*
                 :overwrite-changes *force-overwrite*} ; capture bound values
                options))]
           v))]
    f))

(defn with-remote-file
  "Function to call f with a local copy of the sessioned remote path.
   f should be a function taking [session local-path & _], where local-path will
   be a File with a copy of the remote file (which will be unlinked after
   calling f."
  [f path & args]
  (let [local-path (tmpfile)]
    (phase-pipeline with-remote-file-fn {:local-path local-path}
      (transfer-file-to-local path local-path)
      (apply f local-path args)
      (delete-local-path local-path))))

(defn remote-file-content
  "Return a function that returns the content of a file, when used inside
   another action."
  [path]
  (let-s
    [nv (exec-script (~lib/cat ~path))
     c (return-value-expr [nv] (:out nv))]
    c))

;;; # Remote Directory Content

(defn remote-directory
  "Specify the contents of remote directory.

   Options:
    - :url              - a url to download content from
    - :unpack           - how download should be extracts (default :tar)
    - :tar-options      - options to pass to tar (default \"xz\")
    - :unzip-options    - options to pass to unzip (default \"-o\")
    - :jar-options      - options to pass to unzip (default \"xf\")
                          jar does not support stripping path components
    - :strip-components - number of path compnents to remove when unpacking
    - :md5              - md5 of file to unpack
    - :md5-url          - url of md5 file for file to unpack

   Ownership options:
    - :owner            - owner of files
    - :group            - group of files
    - :recursive        - flag to recursively set owner and group

   To install the content of an url pointing at a tar file, specify the :url
   option.
       (remote-directory session path
          :url \"http://a.com/path/file.tgz\")

   If there is an md5 url with the tar file's md5, you can specify that as well,
   to prevent unecessary downloads and verify the content.
       (remote-directory session path
          :url \"http://a.com/path/file.tgz\"
          :md5-url \"http://a.com/path/file.md5\")

   To install the content of an url pointing at a zip file, specify the :url
   option and :unpack :unzip.
       (remote-directory session path
          :url \"http://a.com/path/file.\"
          :unpack :unzip)"
  [path & {:keys [action url local-file remote-file
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
  (verify-local-file-exists local-file)
  (let-s
    [_ (m-when local-file
         (transfer-file local-file (str path "-content")))
     f (with-action-options local-file-options
         (let-s
           [v (remote-directory-action
               path
               (merge
                {:install-new-files *install-new-files*
                 :overwrite-changes *force-overwrite*} ; capture bound values
                options))]
           v))]
    f))


;;; # Packages
(defaction package
  "Install or remove a package.

   Options
    - :action [:install | :remove | :upgrade]
    - :purge [true|false]         when removing, whether to remove all config
    - :enable [repo|(seq repo)]   enable specific repository
    - :disable [repo|(seq repo)]  disable specific repository
    - :priority n                 priority (0-100, default 50)

   Package management occurs in one shot, so that the package manager can
   maintain a consistent view."
  {:execution :aggregated}
  [package-name & {:keys [action y force purge enable disable priority]
                   :or {action :install
                        y true
                        priority 50}}])

(defn packages
  "Install a list of packages keyed on packager.
       (packages session
         :yum [\"git\" \"git-email\"]
         :aptitude [\"git-core\" \"git-email\"])"
  [& {:keys [yum aptitude pacman brew] :as options}]
  (phase-pipeline packages {}
    [packager packager]
    (map package (options packager))))

(defaction package-manager
  "Package manager controls.

   `action` is one of the following:
   - :update          - update the list of available packages
   - :list-installed  - output a list of the installed packages
   - :add-scope       - enable a scope (eg. multiverse, non-free)

   To refresh the list of packages known to the pakage manager:
       (package-manager session :update)

   To enable multiverse on ubuntu:
       (package-manager session :add-scope :scope :multiverse)

   To enable non-free on debian:
       (package-manager session :add-scope :scope :non-free)"
  {:always-before package
   :execution :aggregated}
  [action & options])

(def package-source-changed-flag "packagesourcechanged")

(defaction package-source
  "Control package sources.
   Options are the package manager keywords, each specifying a map of
   packager specific options.

   :aptitude
     - :source-type string   - source type (deb)
     - :url url              - repository url
     - :scopes seq           - scopes to enable for repository
     - :key-url url          - url for key
     - :key-id id            - id for key to look it up from keyserver

   :yum
     - :name                 - repository name
     - :url url          - repository base url
     - :gpgkey url           - gpg key url for repository

   Example
       (package-source \"Partner\"
         :aptitude {:url \"http://archive.canonical.com/\"
                    :scopes [\"partner\"]})"
  {:always-before #{package-manager package}
   :execution :aggregated}
  [name & {:keys [aptitude yum]}])

(defaction add-rpm
  "Add an rpm.  Source options are as for remote file."
  [rpm-name & {:as options}])

(defaction install-deb
  "Add a deb.  Source options are as for remote file."
  [deb-name & {:as options}])

(defaction minimal-packages
  "Add minimal packages for pallet to function"
  {:always-before #{package-manager package-source package}}
  [])


;;; # Synch local file to remote
(defaction rsync
  "Use rsync to copy files from local-path to remote-path"
  [local-path remote-path {:keys [port]}])

(defn rsync-directory
  "Rsync from a local directory to a remote directory."
  [from to & {:keys [owner group mode port] :as options}]
  (phase-pipeline rsync-directory-fn {:name :rsync-directory}
    ;; would like to ensure rsync is installed, but this requires
    ;; root permissions, and doesn't work when this is run without
    ;; root permision
    ;; (package "rsync")
    (directory to :owner owner :group group :mode mode)
    (rsync from to options)))

;;; # Users and Groups
(defaction group
  "User Group Management."
  [groupname & {:keys [action system gid password]
                :or {action :manage}
                :as options}])

(defaction user
  "User management."
  {:execution :aggregated
   :always-after #{group}}
  [username & {:keys [action shell base-dir home system create-home
                      password shell comment groups remove force append]
               :or {action :manage}
               :as options}])

;;; # Services
(defaction service
  "Control services.

   - :action  accepts either startstop, restart, enable or disable keywords.
   - :if-flag  makes start, stop, and restart confitional on the specified flag
               as set, for example, by remote-file :flag-on-changed
   - :sequence-start  a sequence of [sequence-number level level ...], where
                      sequence number determines the order in which services
                      are started within a level.
   - :service-impl    either :initd or :upstart"
  [service-name & {:keys [action if-flag if-stopped service-impl]
                   :or {action :start service-impl :initd}
                   :as options}])

(defmacro with-service-restart
  "Stop the given service, execute the body, and then restart."
  [service-name & body]
  `(let [service# ~service-name]
     (phase-pipeline with-restart {:service service#}
       (service service# :action :stop)
       ~@body
       (service service# :action :start))))

(defn service-script
  "Install a service script.  Sources as for remote-file."
  [service-name & {:keys [action url local-file remote-file link
                          content literal template values md5 md5-url
                          force service-impl]
                   :or {action :create service-impl :initd}
                   :as options}]
  (phase-pipeline init-script {}
    (apply-map
     pallet.actions/remote-file
     (service-script-path service-impl service-name)
     :owner "root" :group "root" :mode "0755"
     (merge {:action action} options))))

;;; # Retry
;;; TODO: convert to use a nested scope in the action-plan
(defn loop-until
  {:no-doc true}
  [service-name condition max-retries standoff]
  (exec-checked-script
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
       (sleep ~standoff)))))

(defmacro retry-until
  "Repeat an action until it succeeds"
  [{:keys [max-retries standoff service-name]
    :or {max-retries 5 standoff 2}}
   condition]
  (let [service-name (or service-name "retryable")]
    `(loop-until ~service-name ~condition ~max-retries ~standoff)))

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
  [roles & body]
  `(phase-pipeline-no-context
    on-one-node {:roles roles}
    [target# target
     role->nodes# (role->nodes-map)]
    (pipeline-when
     (= target# (one-node-filter role->nodes# ~roles))
     ~@body)))
