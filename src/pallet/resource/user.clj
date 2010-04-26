(ns pallet.resource.user
  "User management resource."
  (:require pallet.compat)
  (:use pallet.script
        [pallet.resource :only [defresource defaggregate]]
        pallet.stevedore
        [clojure.contrib.def :only [defvar-]]
        clojure.contrib.logging))

(pallet.compat/require-contrib)

(defscript user-exists? [name])
(defscript modify-user [name options])
(defscript create-user [name options])
(defscript remove-user [name options])
(defscript lock-user [name])
(defscript unlock-user [name])
(defscript user-home [username])
(defscript current-user [])

(defimpl user-exists? :default [username]
  (getent passwd ~username))

(defimpl create-user :default [username options]
  (useradd ~(map-to-arg-string options) ~username))

(defimpl modify-user :default [username options]
  (usermod ~(map-to-arg-string options) ~username))

(defimpl remove-user :default [username options]
  (userdel ~(map-to-arg-string options) ~username))

(defimpl lock-user :default [username]
  (usermod --lock ~username))

(defimpl unlock-user :default [username]
  (usermod --unlock ~username))

(defimpl user-home :default [username]
  @(getent passwd ~username | cut "-d:" "-f6"))

(defimpl current-user :default []
  @(whoami))

(defvar- shell-names
  {:bash "/bin/bash" :csh "/bin/csh" :ksh "/bin/ksh" :rsh "/bin/rsh"
   :sh "/bin/sh" :tcsh "/bin/tcsh" :zsh "/bin/zsh" :false "/bin/false"}
  "Map for looking up shell path based on keyword.")

(defn apply-user
  "Require a user"
  [username & options]
  (let [opts (if options (apply assoc {} options))
        opts (merge {:action :manage} opts)
        opts (merge opts {:shell (get shell-names (opts :shell) (opts :shell))})
        action (get opts :action)]
    (condp = action
      :create
      (script
       (if-not (user-exists? ~username)
         (create-user
          ~username ~(select-keys opts [:base-dir :home :system :create-home
                                        :password :shell]))))
      :manage
      (script
       (if (user-exists? ~username)
         (modify-user
          ~username ~(select-keys opts [:home :shell :comment]))
         (create-user
          ~username ~(select-keys opts [:base-dir :home :system :comment
                                        :create-home :pasword :shell]))))
      :lock
      (script
       (if (user-exists? ~username)
         (lock-user ~username)))
      :unlock
      (script
       (if (user-exists? ~username)
         (unlock-user ~username)))
      :remove
      (script
       (if (user-exists? ~username)
         (remove-user ~username ~(select-keys opts [:remove :force]))))
      (throw (IllegalArgumentException.
              (str action " is not a valid action for user resource"))))))

(def user-args (atom []))

(defn- apply-users [user-args]
  (string/join \newline (map #(apply apply-user %) user-args)))

(defaggregate user "User management."
  apply-users [username & options])
