(ns pallet.actions.impl
  "Implementation namespace for Pallet's action primitives."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [pallet.common.context :refer [throw-map]]
   [pallet.context :as context]
   [pallet.session :refer [user]]
   [pallet.script.lib :as lib]
   [pallet.script.lib :refer [file state-root user-home]]
   [pallet.stevedore :refer [fragment script]]))

(def ^:dynamic *script-location-info* true)

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

;;; # File names for transfers

;;; These paths create a parallel directory tree under "/var/lib/pallet" which
;;; contains the up/downloaded files, the md5s and the installed file history.

;;; To facilitate md5 checks the basename of the generated copy-filename should
;;; match the original basename, and the md5 file should be in the same
;;; directory.

;;; Note that we can not use remote evaluated expressions in these paths, as
;;; they are used locally.

(defn- adjust-root
  [^String script-dir ^String path]
  (if (.startsWith path "/")
    path
    (fragment
     (file ~(or script-dir
                ;; use /home so we have a path tha doesn't
                ;; involve shell vars
                (str "/home/" (:username (user {})))) ;; TODO FIXME
           ~path))))

(defn new-filename
  "Generate a temporary file name for a given path."
  [script-dir path]
  (fragment
   (str (state-root) "/pallet" ~(str (adjust-root script-dir path) ".new"))))

(defn md5-filename
  "Generate a md5 file name for a given path."
  [script-dir path]
  (fragment
   (str (state-root) "/pallet" ~(str (adjust-root script-dir path) ".md5"))))

(defn copy-filename
  "Generate a file name for a copy of the given path."
  [script-dir path]
  (fragment (str (state-root) "/pallet" ~(adjust-root script-dir path))))
