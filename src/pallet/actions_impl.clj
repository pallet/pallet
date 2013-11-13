(ns pallet.actions-impl
  "Implementation namespace for Pallet's action primitives."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [pallet.action :refer [defaction]]
   [pallet.common.context :refer [throw-map]]
   [pallet.context :as context]
   [pallet.core.session :as session :refer [session]]
   [pallet.crate :refer [admin-user]]
   [pallet.script.lib :as lib]
   [pallet.script.lib :refer [file state-root user-home]]
   [pallet.stevedore :refer [fragment script]]
   [pallet.utils :refer [base64-md5]]))

(def ^:dynamic *script-location-info* true)

(defaction if-action
  "An 'if' flow control action, that claims the next (up to two) nested scopes."
  [condition])

(defn verify-local-file-exists
  [local-file]
  (when-let [^java.io.File f (and local-file (io/file local-file))]
    (when (not (and (.exists f) (.isFile f) (.canRead f)))
      (throw-map
       (format
               (str "'%s' does not exist, is a directory, or is unreadable; "
                    "cannot register it for transfer.")
               local-file)
       {:local-file local-file}))))

(def ^{:doc "Var to control installation of new file content on remote nodes."
       :dynamic true}
  *install-new-files* true)

(def ^{:doc "Var to control overwriting of modified file content on remote
             nodes."
       :dynamic true}
  *force-overwrite* false)

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

(defn init-script-path
  "Path to the specified initd script"
  [service-name]
  (str (script (~lib/etc-init)) "/" service-name))

;;; # Service Supervision
;;; TODO - remove these
(defmulti service-script-path
  (fn [service-impl service-name] service-impl))

(defmethod service-script-path :initd
  [_ service-name]
  (str (fragment (lib/etc-init)) "/" service-name))

(defmethod service-script-path :upstart
  [_ service-name]
  (str (fragment (lib/upstart-script-dir)) "/" service-name ".conf"))


;;; # File Names for Pallet Internals
(defn- adjust-root
  [^String script-dir ^String path]
  (if (or (.startsWith path "/")
          (.startsWith path "$"))
    path
    (fragment
     (file ~(or script-dir
                ;; use /home so we have a path tha doesn't
                ;; involve shell vars
                (str "/home/" (:username (admin-user))))
           ~path))))

(defn pallet-state-root
  "This is where pallet will keep backups and md5 files."
  [session]
  (or (:state-root (session/admin-user session))
      (fragment (file (state-root) "pallet"))))

;;; ## File Names for Transfers from localhost

;;; These provide an upload path that will have ownership assigned
;;; to the admin user, so that the admin user can always overwrite files
;;; at these paths (ie. for re-upload).

(defn upload-filename
  "Generate a temporary file name for a given path."
  [session script-dir path]
  (str (pallet-state-root session)
       (adjust-root nil (base64-md5 path))))

;; (defn upload-md5-filename
;;   "Generate a temporary file name for a given path."
;;   [session script-dir path]
;;   (str (pallet-state-root session)
;;        (str "/home/" (:username (admin-user))) "/.pallet-uploads"
;;        (adjust-root script-dir path)
;;        ".md5"))

;;; ## File Names for Pallet State

;;; These paths create a parallel directory tree under "/var/lib/pallet" which
;;; contains the up/downloaded files, the md5s and the installed file history.

;;; To facilitate md5 checks the basename of the generated copy-filename should
;;; match the original basename, and the md5 file should be in the same
;;; directory.

;;; Note that we can not use remote evaluated expressions in these paths, as
;;; they are used locally.


(defn new-filename
  "Generate a temporary file name for a given path."
  [session script-dir path]
  (str (pallet-state-root session) (adjust-root script-dir path) ".new"))

(defn md5-filename
  "Generate a md5 file name for a given path."
  [session script-dir path]
  (str (pallet-state-root session) (adjust-root script-dir path) ".md5"))

(defn copy-filename
  "Generate a file name for a copy of the given path."
  [session script-dir path]
  (str (pallet-state-root session) (adjust-root script-dir path)))
