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
(defaction packages [session package-names options])
(defaction package-manager [session action options])
(defaction package-repository [packager options])
(defaction package-source [session name options])

(defaction add-rpm [session rpm-name options])
(defaction install-deb [session deb-name options])
(defaction debconf-set-selections [session options])

;;; ## Files and Directories
(defaction file [session path options])
(defaction symbolic-link [session from name options])
(defaction fifo [session path options])
(defaction sed [session path exprs-map options])

(defaction directory [session dir-path options])

;;; ## Remote File Contents
(defaction transfer-file
  "Function to transfer a local file to a remote path.
Prefer remote-file or remote-directory over direct use of this action."
  [session local-path remote-path])

(defaction transfer-file-to-local
  "Function to transfer a remote file to a local path."
  [session remote-path local-path])

(defaction remote-file [session path options])
(defaction remote-directory [session path options])
(defaction wait-for-file [session path options])

;;; ## Users and groups
(defaction group [session groupname options])
(defaction user [session username options])

;;; # Services
(defaction service [session service-name options])

;;; ## Rsync
(defaction rsync [session local-path remote-path options])
(defaction rsync-to-local [session remote-path local-path options])
