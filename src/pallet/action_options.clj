(ns pallet.action-options
  "Options for controlling the behaviour of actions."
  (:require
   [pallet.session :as session]))

;;; # Action Options

;;; Options for actions can be overridden by setting the options map
;;; on the session.
(def ^{:no-doc true :internal true} action-options-key :action-options)

(defn action-options
  "Return any action-options currently defined on the session."
  [session]
  (session/action-options session))

;; (defn get-action-options
;;   "Return any action-options currently defined on the session."
;;   []
;;   (action-options (session)))

(defn merge-action-options
  "Update any precedence modifiers defined on the session"
  [session m]
  (update-in session [:execution-state action-options-key] merge m))

(defn assoc-action-options
  "Set precedence modifiers defined on the session."
  [session & m]
  (apply update-in session [:execution-state action-options-key] assoc m))

(defmacro ^{:indent 1} with-action-options
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
  `(let [~session (merge-action-options ~session ~m)]
     ~@body))
