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
   [pallet.stevedore :as stevedore :refer [fragment script]]))

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

;;; # Script Combinators
(defn context-string
  "The string that is used to represent the phase context for :in-sequence
  actions."
  {:no-doc true}
  []
  (when-let [ctxt (seq (context/phase-contexts))]
    (str (string/join ": " ctxt) ": ")))

(defmacro checked-script
  "Return a stevedore script that uses the current context to label the
   action"
  [name & script]
  `(stevedore/checked-script
    (str (context-string) ~name)
    ~@script))

(defmacro checked-commands*
  "Return a stevedore script that uses the current context to label the
   action"
  [name scripts]
  `(stevedore/checked-commands*
    (str (context-string) ~name)
    ~scripts))

(defn checked-commands
  "Return a stevedore script that uses the current context to label the
   action"
  [name & script]
  (checked-commands* name script))
