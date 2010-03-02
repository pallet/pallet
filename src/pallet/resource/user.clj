(ns #^{ :doc "User management resource."}
  pallet.resource.user
  (:require [clojure.contrib.str-utils2 :as string])
  (:use pallet.script
        [pallet.resource :only [defresource]]
        pallet.stevedore
        clojure.contrib.logging))

(defscript user-exists? [name])
(defscript modify-user [name options])
(defscript create-user [name options])
(defscript remove-user [name options])
(defscript lock-user [name options])
(defscript unlock-user [name options])
(defscript user-home [username])

(defimpl user-exists? :default [username]
  (grep ~(str \" "^" username ":" \") "/etc/passwd"))

(defimpl create-user :default [username options]
  (useradd ~(map-to-arg-string options) ~username))

(defimpl modify-user :default [username options]
  (usermod ~(map-to-arg-string options) ~username))

(defimpl remove-user :default [username options]
  (userdel ~(map-to-arg-string options) ~username))

(defimpl lock-user :default [username options]
  (usermod --lock ~username))

(defimpl unlock-user :default [username options]
  (usermod --unlock ~username))

(defimpl user-home :default [username]
  @(getent passwd ~username | cut "-d:" "-f6"))

(defn apply-user
  "Require a user"
  [username & options]
  (let [opts (if options (apply assoc {} options))
        opts (merge opts {:action :manage})
        action (get opts :action)]
    (condp = action
      :create
      (script
       (if-not (user-exists? ~username)
         (create-user
          ~username ~(select-keys opts [:base-dir :home :system :create-home
                                        :password]))))
      :manage
      (script
       (if (user-exists? ~username)
         (modify-user
          ~username ~(select-keys opts [:home]))
         (create-user
          ~username ~(select-keys opts [:base-dir :home :system
                                        :create-home :pasword]))))
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
         (remove-user ~username ~~(select-keys opts [:remove :force]))))
      (throw (IllegalArgumentException.
              (str action " is not a valid action for user resource"))))))

(def user-args (atom []))

(defn- apply-users [user-args]
  (info "apply-users")
  (string/join \newline (map #(apply apply-user %) user-args)))


(defresource user "User management.
" user-args apply-users [username & options])



