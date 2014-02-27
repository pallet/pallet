(ns pallet.action-options
  "Options for controlling the behaviour of actions."
  (:require
   [pallet.session :as session]))

;;; # Action Options

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
  login environment and you want the affect to be visible immediately."
  [session m & body]
  (when-not (symbol? session)
    (throw
     (Exception. "with-action-options expects a symbol as first argument.")))
  `(let [~session (session/merge-action-options ~session ~m)]
     ~@body))
