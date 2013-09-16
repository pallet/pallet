(ns pallet.actions.decl
  "Action declarations"
  (:require
   [clojure.java.io :as io]
   [pallet.action :refer [defaction]]
   [pallet.action-plan :refer [checked-script]]
   [pallet.actions-impl :refer :all]
   [pallet.argument :as argument :refer [delayed delayed-argument?]]
   [pallet.stevedore :as stevedore]))

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
  (let [file (.getName (io/file *file*))
        line (:line (meta &form))]
    `(exec-script*
      (delayed [_#]
               (checked-script
                ~(if *script-location-info*
                   `(str ~script-name " (" ~file ":" ~line ")")
                   script-name)
                ~@script)))))


(defaction if-action
  "An 'if' flow control action, that claims the next (up to two) nested scopes."
  [condition])

(defaction remote-file-action
  "An action that implements most of remote-file, but requires a helper in order
to deal with local file transfer."
  [path {:keys [action url local-file
                remote-file link content literal template values md5 md5-url
                owner group mode force blob blobstore overwrite-changes
                install-new-files no-versioning max-versions
                flag-on-changed force insecure]
         :or {action :create max-versions 5}
         :as options}])


(defaction remote-directory-action
  [path {:keys [action url local-file remote-file
                unpack tar-options unzip-options jar-options
                strip-components md5 md5-url owner group recursive]
         :or {action :create
              tar-options "xz"
              unzip-options "-o"
              jar-options "xf"
              strip-components 1
              recursive true}
         :as options}])

(def package-source-changed-flag "packagesourcechanged")

(defaction packages-action
  "Install a list of packages."
  [packager packages])

(defaction package-repository-action
  "Control package repository.
   Options are a map of packager specific options.

## aptitude and apt-get

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

## yum

`:name name`
: repository name

`:url url-string`
: repository base url

`:gpgkey url-string`
: gpg key url for repository

## Example

    (package-repository
       {:repository-name \"Partner\"
        :url \"http://archive.canonical.com/\"
        :scopes [\"partner\"]})"
  [packager {:as options}])
