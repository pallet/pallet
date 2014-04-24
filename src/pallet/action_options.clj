(ns pallet.action-options
  "Options for controlling the behaviour of actions."
  (:require
   [pallet.session :as session]
   [pallet.user :refer [User]]
   [schema.core :as schema :refer [check optional-key validate]]))

;;; # Action Options

(def ActionOptions
  {(optional-key :error-on-non-zero-exit) schema/Bool
   (optional-key :new-login-after-action) schema/Bool
   (optional-key :record-all) schema/Bool
   (optional-key :script-comments) schema/Bool
   (optional-key :script-dir) String
   (optional-key :script-env) {(schema/either schema/Keyword String) schema/Any}
   (optional-key :script-env-fwd) [(schema/either schema/Keyword String)]
   (optional-key :script-prefix) schema/Keyword
   (optional-key :sudo-user) String
   (optional-key :user) User
   (optional-key :action-id) schema/Keyword})

(defn action-options
  "Return any action-options currently defined on the session."
  [session]
  (session/action-options session))

(defmacro with-action-options
  "Set up local options for actions, and allow override of user
options.

`:script-dir`
: Controls the directory the script is executed in.

`:sudo-user`
: Controls the user the action runs as.

`:script-prefix`
: Specify a prefix for the script. Disable sudo using `:no-sudo`. Defaults to
  `:sudo`.

`:script-env`
: Specify a map of environment variables.

`:script-comments`
: Control the generation of script line number comments

`:new-login-after-action`
: Force a new ssh login after the action.  Useful if the action effects the
  login environment and you want the affect to be visible immediately.

`:record-all`
: control the recording of action-option results.  When set to false,
only the action summary and any error is recorded.  Defaults to true."
  [session m & body]
  (when-not (symbol? session)
    (throw
     (Exception. "with-action-options expects a symbol as first argument.")))
  `(let [m# ~m]
     (validate ActionOptions m#)
     (let [~session (session/merge-action-options ~session ~m)]
         ~@body)))
