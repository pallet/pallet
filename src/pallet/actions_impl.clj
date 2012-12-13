(ns pallet.actions-impl
  "Implementation namespace for Pallet's action primitives."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [pallet.context :as context]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore])
  (:use
   [pallet.action :only [defaction]]
   [pallet.common.context :only [throw-map]]
   [pallet.utils :only [apply-map]]))

(defmacro checked-script
  "Return a stevedore script that uses the current context to label the
   action"
  [name & script]
  `(stevedore/checked-script
    (if-let [context# (seq (context/phase-contexts))]
      (str (string/join ": " context#) "\n" ~name)
      ~name)
    ~@script))

(defaction if-action
  "An 'if' flow control action, that claims the next (up to two) nested scopes."
  [condition])

(defn verify-local-file-exists
  [local-file]
  (when-let [f (and local-file (io/file local-file))]
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
  (str (stevedore/script (~lib/etc-init)) "/" service-name))


(defmulti service-script-path
  (fn [service-impl service-name] service-impl))

(defmethod service-script-path :initd
  [_ service-name]
  (str (stevedore/script (~lib/etc-init)) "/" service-name))

(defmethod service-script-path :upstart
  [_ service-name]
  (str (stevedore/script (~lib/upstart-script-dir)) "/" service-name ".conf"))
