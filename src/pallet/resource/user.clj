(ns pallet.resource.user
  "User management resource."
  (:use
   pallet.script
   [pallet.resource :only [defresource defaggregate]]
   pallet.stevedore
   [clojure.contrib.def :only [defvar-]]
   clojure.contrib.logging)
  (:require
   [clojure.contrib.string :as string]))

(defscript user-exists? [name])

(defscript modify-user [name options])
(defscript create-user [name options])
(defscript remove-user [name options])
(defscript lock-user [name])
(defscript unlock-user [name])
(defscript user-home [username])
(defscript current-user [])

(defscript group-exists? [name])
(defscript modify-group [name options])
(defscript create-group [name options])
(defscript remove-group [name options])

(defimpl user-exists? :default [username]
  (getent passwd ~username))

(defimpl create-user :default [username options]
  ("/usr/sbin/useradd" ~(map-to-arg-string options) ~username))

(defimpl modify-user :default [username options]
  ("/usr/sbin/usermod" ~(map-to-arg-string options) ~username))

(defimpl remove-user :default [username options]
  ("/usr/sbin/userdel" ~(map-to-arg-string options) ~username))

(defimpl lock-user :default [username]
  ("/usr/sbin/usermod" --lock ~username))

(defimpl unlock-user :default [username]
  ("/usr/sbin/usermod" --unlock ~username))

(defimpl user-home :default [username]
  @(getent passwd ~username | cut "-d:" "-f6"))

(defimpl user-home [:os-x] [username]
  @(pipe
    (dscl localhost -read ~(str "/Local/Default/Users/" username)
          "dsAttrTypeNative:home")
    (cut -d "' '" -f 2)))

(defimpl current-user :default []
  @(whoami))

(defimpl group-exists? :default [name]
  (getent group ~name))

(defimpl create-group :default [groupname options]
  ("/usr/sbin/groupadd" ~(map-to-arg-string options) ~groupname))

(defimpl modify-group :default [groupname options]
  ("/usr/sbin/groupmod" ~(map-to-arg-string options) ~groupname))

(defimpl remove-group :default [groupname options]
  ("/usr/sbin/groupdel" ~(map-to-arg-string options) ~groupname))

(defvar- shell-names
  {:bash "/bin/bash" :csh "/bin/csh" :ksh "/bin/ksh" :rsh "/bin/rsh"
   :sh "/bin/sh" :tcsh "/bin/tcsh" :zsh "/bin/zsh" :false "/bin/false"}
  "Map for looking up shell path based on keyword.")

(defn user*
  "Require a user"
  [request username & {:keys [action shell base-dir home system create-home
                              password shell comment groups remove force]
                       :or {action :manage}
                       :as options}]
  (let [opts (merge options {:shell (get shell-names shell shell)})]
    (case action
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
          ~username ~(select-keys opts [:home :shell :comment :groups]))
         (create-user
          ~username ~(select-keys opts [:base-dir :home :system :comment
                                        :create-home :pasword :shell
                                        :groups]))))
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


(defaggregate user
  "User management."
  {:copy-arglist pallet.resource.user/user*}
  (user-combiner
   [request user-args]
   (string/join \newline (map #(apply user* request %) user-args))))


(defresource group
  "User Group Management."
  (group*
   [request groupname & {:keys [action system gid password]
                         :or {action :manage}
                         :as options}]
   (case action
     :create
     (script
      (if-not (group-exists? ~groupname)
        (create-group
         ~groupname ~(select-keys options [:system :gid :password]))))
     :manage
     (script
      (if (group-exists? ~groupname)
        (modify-group
         ~groupname ~(select-keys options [:gid :password]))
        (create-group
         ~groupname ~(select-keys options [:system :gid :password]))))
     :remove
     (script
      (if (group-exists? ~groupname)
        (remove-group ~groupname {})))
     (throw (IllegalArgumentException.
             (str action " is not a valid action for group resource"))))))
