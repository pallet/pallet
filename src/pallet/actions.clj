(ns pallet.actions
  "Pallet's action primitives."
  (:require
   [clj-schema.schema
    :refer [constraints def-map-schema map-schema optional-path sequence-of]]
   [clojure.java.io :as io]
   [clojure.set :refer [intersection]]
   [clojure.string :refer [trim]]
   [clojure.tools.logging :as logging]
   [pallet.action
    :refer [clj-action defaction enter-scope get-action-options leave-scope
            with-action-options]]
   [pallet.action-plan :refer [checked-script]]
   [pallet.actions-impl :refer :all]
   [pallet.argument :as argument :refer [delayed delayed-argument?]]
   [pallet.contracts :refer [any-value check-spec]]
   [pallet.crate :refer [admin-user packager phase-context role->nodes-map
                         target]]
   [pallet.node-value :refer [node-value]]
   [pallet.script.lib :as lib :refer [set-flag-value]]
   [pallet.stevedore :as stevedore :refer [with-source-line-comments]]
   [pallet.utils :refer [apply-map log-multiline tmpfile]])
  (:import clojure.lang.Keyword))

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
  {:pallet/plan-fn true}
  [& script]
  `(exec-script* (delayed [_#] (stevedore/script ~@script))))

(defmacro ^{:requires [#'checked-script]}
  exec-checked-script
  "Execute a bash script remotely, throwing if any element of the
   script fails. The script is expressed in stevedore."
  {:pallet/plan-fn true}
  [script-name & script]
  `(exec-script*
    (delayed [_#]
      (checked-script
       ~(if *script-location-info*
          `(str ~script-name
                " (" ~(.getName (io/file *file*)) ":" ~(:line (meta &form)) ")")
          script-name)
       ~@script))))

;;; # Wrap arbitrary code
(defmacro as-action
  "Wrap arbitrary clojure code to be run as an action"
  {:pallet/plan-fn true}
  [& body]
  `((clj-action [~'&session]
      (binding [pallet.argument/*session* ~'&session]
        [(do ~@body) ~'&session]))))

;;; # Flow Control
(defmacro plan-when
  "Execute the crate-fns-or-actions, only when condition is true."
  {:indent 1
   :pallet/plan-fn true}
  [condition & crate-fns-or-actions]
  (let [nv (gensym "nv")
        nv-kw (keyword (name nv))
        is-stevedore? (and (sequential? condition)
                           (symbol? (first condition))
                           (#{#'stevedore/script #'stevedore/fragment}
                            (resolve (first condition))))
        is-script? (or (string? condition) is-stevedore?)]
    `(phase-context plan-when {:condition ~(list 'quote condition)}
       (let [~@(when is-script?
                 [nv `(with-source-line-comments false
                        (exec-checked-script
                         (str "Check " ~condition)
                         (~(list `unquote 'pallet.script.lib/set-flag-value)
                          ~(name nv-kw)
                          @(do
                             ~@(if is-stevedore?
                                 (rest condition)
                                 ["test" condition])
                             (~'println @~'?)))))] )]
         (if-action ~(if is-script?
                       `(delayed [s#]
                                 (= (-> (node-value ~nv s#) :flag-values ~nv-kw)
                                    "0"))
                       `(delayed [~'&session] ~condition))))
       (enter-scope)
       ~@crate-fns-or-actions
       (leave-scope))))

(defmacro plan-when-not
  "Execute the crate-fns-or-actions, only when condition is false."
  {:indent 1
   :pallet/plan-fn true}
  [condition & crate-fns-or-actions]
  (let [nv (gensym "nv")
        nv-kw (keyword (name nv))
        is-stevedore? (and (sequential? condition)
                           (symbol? (first condition))
                           (#{#'stevedore/script #'stevedore/fragment}
                            (resolve (first condition))))
        is-script? (or (string? condition) is-stevedore?)]
    `(phase-context plan-when-not {:condition ~(list 'quote condition)}
       (let [~@(when is-script?
                 [nv `(with-source-line-comments false
                        (exec-checked-script
                         (str "Check not " ~condition)
                         (~(list `unquote `set-flag-value)
                          ~(name nv-kw)
                          @(do
                             ~@(if is-stevedore?
                                 (rest condition)
                                 ["test" condition])
                             (~'println @~'?)))))])]
         (if-action ~(if is-script?
                       `(delayed [s#]
                          (= (-> (node-value ~nv s#) :flag-values ~nv-kw)
                             "0"))
                       `(delayed [~'&session] ~condition))))
       (enter-scope)
       (leave-scope)
       (enter-scope)
       ~@crate-fns-or-actions
       (leave-scope))))

(defmacro with-action-values
  "Creates an action that can transform action return values.  The return
value is itself an action return value."
  {:pallet/plan-fn true}
  [[& return-values] & body]
  (let [session (gensym "session")]
    `((clj-action [~session]
       [(let [~@(mapcat #(vector % `(node-value ~% ~session)) return-values)]
          (logging/tracef "with-action-values %s" ~(vec return-values))
          ~@body)
        ~session]))))

(defmacro return-value-expr
  "Creates an action that can transform return values"
  {:pallet/plan-fn true
   :deprecated "0.8.0-beta.8"}
  [[& return-values] & body]
  (let [session (gensym "session")]
    `((clj-action [~session]
       [(let [~@(mapcat #(vector % `(node-value ~% ~session)) return-values)]
          (logging/tracef "return-value-expr %s" ~(vec return-values))
          ~@body)
        ~session]))))

(defaction assoc-settings
  "Set the settings for the specified host facility. The instance-id allows
   the specification of specific instance of the facility (the default is
   :default)."
  [facility kv-pairs & {:keys [instance-id]}])

(defaction assoc-in-settings
  "Put the specified `value` (possible a `NodeValue`) into the settings
  for `path`, a vector of `[facility & keys]`, specifying a facility an a path."
  [path value & {:keys [instance-id]}])

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

(def-map-schema remote-file-arguments
  :strict
  (constraints
   (fn [m] (some (set content-options) (keys m))))
  [(optional-path [:local-file]) String
   (optional-path [:remote-file]) String
   (optional-path [:url]) String
   (optional-path [:md5]) String
   (optional-path [:md5-url]) String
   (optional-path [:content]) [:or String delayed-argument?]
   (optional-path [:literal]) any-value
   (optional-path [:template]) String
   (optional-path [:values]) (map-schema :loose [])
   (optional-path [:action]) Keyword
   (optional-path [:blob]) (map-schema :strict
                                       [[:container] String [:path] String])
   (optional-path [:blobstore]) any-value  ; cheating to avoid adding a reqiure
   (optional-path [:insecure]) any-value
   (optional-path [:overwrite-changes]) any-value
   (optional-path [:no-versioning]) any-value
   (optional-path [:max-versions]) Number
   (optional-path [:flag-on-changed]) String
   (optional-path [:owner]) String
   (optional-path [:group]) String
   (optional-path [:mode]) [:or String Number]
   (optional-path [:force]) any-value
   (optional-path [:verify]) any-value])

(defmacro check-remote-file-arguments
  [m]
  (check-spec m `remote-file-arguments &form))

(defaction transfer-file
  "Function to transfer a local file to a remote path.
Prefer remote-file or remote-directory over direct use of this action."
  [local-path remote-path remote-md5-path])

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
  [path & {:keys [action url local-file remote-file link
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
  {:pre [path]}
  (check-remote-file-arguments options)
  (verify-local-file-exists local-file)
  (let [action-options (get-action-options)
        script-dir (:script-dir action-options)
        user (if (= :sudo (:script-prefix action-options :sudo))
               (:sudo-user action-options)
               (:username (admin-user)))
        new-path (new-filename script-dir path)
        md5-path (md5-filename script-dir path)]
    (when local-file
      (transfer-file local-file new-path md5-path))
    ;; we run as root so we don't get permission issues
    (with-action-options (merge
                          {:script-prefix :sudo :sudo-user nil}
                          local-file-options)
      (remote-file-action
       path
       (merge
        {:install-new-files *install-new-files* ; capture bound values
         :overwrite-changes *force-overwrite*
         :owner user}
        options)))))

(defn with-remote-file
  "Function to call f with a local copy of the sessioned remote path.
   f should be a function taking [session local-path & _], where local-path will
   be a File with a copy of the remote file (which will be unlinked after
   calling f."
  {:pallet/plan-fn true}
  [f path & args]
  (let [local-path (tmpfile)]
    (phase-context with-remote-file-fn {:local-path local-path}
      (transfer-file-to-local path local-path)
      (apply f local-path args)
      (delete-local-path local-path))))

(defn remote-file-content
  "Return a function that returns the content of a file, when used inside
   another action."
  {:pallet/plan-fn true}
  [path]
  (let [nv (exec-script (~lib/cat ~path))
        c (with-action-values [nv] (:out nv))]
    c))

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
  {:pallet/plan-fn true}
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
  (let [action-options (get-action-options)
        script-dir (:script-dir action-options)
        user (if (= :sudo (:script-prefix action-options :sudo))
               (:sudo-user action-options)
               (:username (admin-user)))
        new-path (new-filename script-dir path)
        md5-path (md5-filename script-dir path)]
    (when local-file
      (transfer-file local-file new-path md5-path))
    ;; we run as root so we don't get permission issues
    (with-action-options (merge
                          {:script-prefix :sudo :sudo-user nil}
                          local-file-options)
      (remote-directory-action
       path
       (merge
        {:install-new-files *install-new-files* ; capture bound values
         :overwrite-changes *force-overwrite*
         :owner user}
        options)))))

(defaction wait-for-file
  "Wait for a file to exist"
  [path & {:keys [max-retries standoff service-name]
           :or {action :create max-versions 5
                install-new-files true}
           :as options}])

;;; # Packages
(defaction package
  "Install or remove a package.

   Options
    - :action [:install | :remove | :upgrade]
    - :purge [true|false]         when removing, whether to remove all config
    - :enable [repo|(seq repo)]   enable specific repository
    - :disable [repo|(seq repo)]  disable specific repository
    - :priority n                 priority (0-100, default 50)
    - :disable-service-start      disable service startup (default false)

   Package management occurs in one shot, so that the package manager can
   maintain a consistent view."
  {:execution :aggregated
   :always-after #{:package-manager :package-source}}
  [package-name & {:keys [action y force purge enable disable priority]
                   :or {action :install
                        y true
                        priority 50}}])

(defn packages
  "Install a list of packages keyed on packager.
       (packages session
         :yum [\"git\" \"git-email\"]
         :aptitude [\"git-core\" \"git-email\"])"
  {:pallet/plan-fn true}
  [& {:keys [yum aptitude pacman brew] :as options}]
  (phase-context packages {}
    (let [packager (packager)]
      (doseq [p (or (options packager)
                    (when (#{:apt :aptitude} packager)
                      (options (first (disj #{:apt :aptitude} packager)))))]
        (apply-map package p (dissoc options :aptitude :brew :pacman :yum))))))

(defaction package-manager
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
  {:always-before package
   :execution :aggregated}
  [action & options])

(def package-source-changed-flag "packagesourcechanged")

(defaction package-source
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
  [name & {:keys [aptitude yum]}])

(defaction add-rpm
  "Add an rpm.  Source options are as for remote file."
  [rpm-name & {:as options}])

(defaction install-deb
  "Add a deb.  Source options are as for remote file."
  [deb-name & {:as options}])

(defaction debconf-set-selections
  "Set debconf selections.
Specify `:line` as a string, or `:package`, `:question`, `:type` and
`:value` options."
  {:always-before #{package}}
  [{:keys [line package question type value]}])

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
  {:pallet/plan-fn true}
  [from to & {:keys [owner group mode port] :as options}]
  (phase-context rsync-directory-fn {:name :rsync-directory}
    ;; would like to ensure rsync is installed, but this requires
    ;; root permissions, and doesn't work when this is run without
    ;; root permision
    ;; (package "rsync")
    (directory to :owner owner :group group :mode mode)
    (rsync from to options)))

;;; # Users and Groups
(defaction group
  "User Group Management.

`:action`
: One of :create, :manage, :remove.

`:gid`
: specify the group id

`:password`
: An MD5 crypted password for the user.

`:system`
: Specify the user as a system user."
  [groupname & {:keys [action system gid password]
                :or {action :manage}
                :as options}])

(defaction user
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
  {:execution :aggregated
   :always-after #{group}}
  [username & {:keys [action shell base-dir home system create-home
                      password shell comment group groups remove force append]
               :or {action :manage}
               :as options}])

;;; # Services
(defaction service
  "Control services.

   - :action  accepts either startstop, restart, enable or disable keywords.
   - :if-flag  makes start, stop, and restart conditional on the specified flag
               as set, for example, by remote-file :flag-on-changed
   - :sequence-start  a sequence of [sequence-number level level ...], where
                      sequence number determines the order in which services
                      are started within a level.
   - :service-impl    either :initd or :upstart

Deprecated in favour of pallet.crate.service/service."
  [service-name & {:keys [action if-flag if-stopped service-impl]
                   :or {action :start service-impl :initd}
                   :as options}])

(defmacro with-service-restart
  "Stop the given service, execute the body, and then restart."
  [service-name & body]
  `(let [service# ~service-name]
     (phase-context with-restart {:service service#}
       (service service# :action :stop)
       ~@body
       (service service# :action :start))))

(defn service-script
  "Install a service script.  Sources as for remote-file."
  {:pallet/plan-fn true}
  [service-name & {:keys [action url local-file remote-file link
                          content literal template values md5 md5-url
                          force service-impl]
                   :or {action :create service-impl :initd}
                   :as options}]
  (phase-context init-script {}
    (apply-map
     pallet.actions/remote-file
     (service-script-path service-impl service-name)
     :owner "root" :group "root" :mode "0755"
     (merge {:action action} options))))

;;; # Retry
;;; TODO: convert to use a nested scope in the action-plan
(defn loop-until
  {:no-doc true
   :pallet/plan-fn true}
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
       ("sleep" ~standoff)))))

(defmacro retry-until
  "Repeat an action until it succeeds"
  {:pallet/plan-fn true}
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
  {:pallet/plan-fn true}
  [roles & body]
  `(let [target# (target)
         role->nodes# (role->nodes-map)]
     (plan-when
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

;; Local Variables:
;; mode: clojure
;; eval: (define-clojure-indent (plan-when 1)(plan-when-not 1))
;; eval: (define-clojure-indent (with-action-values 1)(with-service-restart 1))
;; End:
