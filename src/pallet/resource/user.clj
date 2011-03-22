(ns pallet.resource.user
  "User management resource."
  (:use
   [pallet.script :only [defscript]]
   [clojure.contrib.def :only [defvar-]])
  (:require
   pallet.resource.script
   [pallet.stevedore :as stevedore]
   [pallet.stevedore.script :as script-impl]
   [pallet.action :as action]
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

(script-impl/defimpl user-exists? :default [username]
  (getent passwd ~username))

(script-impl/defimpl create-user :default [username options]
  ("/usr/sbin/useradd" ~(stevedore/map-to-arg-string options) ~username))

(script-impl/defimpl create-user [#{:rhel :centos :amzn-linux}] [username options]
  ("/usr/sbin/useradd"
   ~(-> options
        (assoc :r (:system options))
        (dissoc :system)
        stevedore/map-to-arg-string)
   ~username))

(script-impl/defimpl modify-user :default [username options]
  ("/usr/sbin/usermod" ~(stevedore/map-to-arg-string options) ~username))

(script-impl/defimpl remove-user :default [username options]
  ("/usr/sbin/userdel" ~(stevedore/map-to-arg-string options) ~username))

(script-impl/defimpl lock-user :default [username]
  ("/usr/sbin/usermod" --lock ~username))

(script-impl/defimpl unlock-user :default [username]
  ("/usr/sbin/usermod" --unlock ~username))

(script-impl/defimpl user-home :default [username]
  @("getent" passwd ~username | "cut" "-d:" "-f6"))

(script-impl/defimpl user-home [:os-x] [username]
  @(pipe
    ("dscl" localhost -read ~(str "/Local/Default/Users/" username)
          "dsAttrTypeNative:home")
    ("cut" -d "' '" -f 2)))

(script-impl/defimpl current-user :default []
  @("whoami"))

(script-impl/defimpl group-exists? :default [name]
  ("getent" group ~name))

(script-impl/defimpl create-group :default [groupname options]
  ("/usr/sbin/groupadd" ~(stevedore/map-to-arg-string options) ~groupname))

(script-impl/defimpl create-group [#{:rhel :centos :amzn-linux}]
  [groupname options]
  ("/usr/sbin/groupadd"
   ~(-> options
        (assoc :r (:system options))
        (dissoc :system)
        stevedore/map-to-arg-string)
   ~groupname))

(script-impl/defimpl modify-group :default [groupname options]
  ("/usr/sbin/groupmod" ~(stevedore/map-to-arg-string options) ~groupname))

(script-impl/defimpl remove-group :default [groupname options]
  ("/usr/sbin/groupdel" ~(stevedore/map-to-arg-string options) ~groupname))

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
      (stevedore/script
       (if-not (user-exists? ~username)
         (create-user
          ~username ~(select-keys opts [:base-dir :home :system :create-home
                                        :password :shell]))))
      :manage
      (stevedore/script
       (if (user-exists? ~username)
         (modify-user
          ~username ~(select-keys opts [:home :shell :comment :groups]))
         (create-user
          ~username ~(select-keys opts [:base-dir :home :system :comment
                                        :create-home :pasword :shell
                                        :groups]))))
      :lock
      (stevedore/script
       (if (user-exists? ~username)
         (lock-user ~username)))
      :unlock
      (stevedore/script
       (if (user-exists? ~username)
         (unlock-user ~username)))
      :remove
      (stevedore/script
       (if (user-exists? ~username)
         (remove-user ~username ~(select-keys opts [:remove :force]))))
      (throw (IllegalArgumentException.
              (str action " is not a valid action for user resource"))))))


(action/def-aggregated-action user
  "User management."
  [request user-args]
  {:arglists (:arglists (meta pallet.resource.user/user*))}
  (string/join \newline (map #(apply user* request %) user-args)))


(action/def-bash-action group
  "User Group Management."
  [request groupname & {:keys [action system gid password]
                        :or {action :manage}
                        :as options}]
  (case action
    :create
    (stevedore/script
     (if-not (group-exists? ~groupname)
       (create-group
        ~groupname ~(select-keys options [:system :gid :password]))))
    :manage
    (stevedore/script
     (if (group-exists? ~groupname)
       (modify-group
        ~groupname ~(select-keys options [:gid :password]))
       (create-group
        ~groupname ~(select-keys options [:system :gid :password]))))
    :remove
    (stevedore/script
     (if (group-exists? ~groupname)
       (remove-group ~groupname {})))
    (throw (IllegalArgumentException.
            (str action " is not a valid action for group resource")))))
