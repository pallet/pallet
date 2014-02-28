(ns pallet.actions.decl
  "Action declarations"
  (:require
   [pallet.action :refer [defaction]]))

;;; # Action Declarations

;;; We have these here, as we want wrappers in pallet.actions that
;;; extract information from the session, and enforce defaults for
;;; optional arguments.

;;; ## Direct Script Execution

;;; Sometimes pallet's other actions will not suffice for what you would like to
;;; achieve, so the exec-script actions allow you to execute arbitrary script.
(defaction exec
  "Execute script on the target node. The `script` is a plain string. `type`
   specifies the script language (default :bash). You can override the
   interpreter path using the `:interpreter` option."
  [session {:keys [language interpreter version] :or {language :bash}} script])

(defaction exec-script*
  "Execute script on the target node. The script is a plain string."
  [session script])

;;; ## Packages
(def package-source-changed-flag "packagesourcechanged")
(defaction package [session package-name options])
(defaction package-manager [session action options])
(defaction package-repository [packager options])
(defaction package-source [session name options])

;;; ## Files and Directories
(defaction file [session path options])
(defaction symbolic-link [session from name options])
(defaction fifo [session path options])
(defaction sed [session path exprs-map options])

(defaction directory [session dir-path options])

;;; ## Remote File Contents
(defaction remote-file
  [session path
   {:keys [action url local-file
           remote-file link content literal template values md5 md5-url
           owner group mode force blob blobstore overwrite-changes
           install-new-files no-versioning max-versions
           flag-on-changed force insecure]
    :or {action :create max-versions 5}
    :as options}])

(defaction remote-directory
  [session path
   {:keys [action url local-file remote-file
           unpack tar-options unzip-options jar-options
           strip-components md5 md5-url owner group recursive]
    :or {action :create
         tar-options "xz"
         unzip-options "-o"
         jar-options "xf"
         strip-components 1
         recursive true}
    :as options}])

;;; ## Rsync
(defaction rsync [session local-path remote-path {:keys [port]}])
(defaction rsync-to-local [session remote-path local-path {:keys [port]}])
