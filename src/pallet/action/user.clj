(ns pallet.action.user
  "User management action."
  (:use
   [pallet.script :only [defscript]])
  (:require
   [pallet.action :as action]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [clojure.string :as string]))


(def
  ^{:doc "Map for looking up shell path based on keyword." :private true}
  shell-names
  {:bash "/bin/bash" :csh "/bin/csh" :ksh "/bin/ksh" :rsh "/bin/rsh"
   :sh "/bin/sh" :tcsh "/bin/tcsh" :zsh "/bin/zsh" :false "/bin/false"})

(defn user*
  "Require a user"
  [session username & {:keys [action shell base-dir home system create-home
                              password shell comment groups remove force append]
                       :or {action :manage}
                       :as options}]
  (let [opts (if-let [shell (get shell-names shell shell)]
               (merge options {:shell shell})
               options)]
    (case action
      :create
      (stevedore/script
       (if-not (~lib/user-exists? ~username)
         (~lib/create-user
          ~username ~(select-keys opts [:base-dir :home :system :comment
                                        :create-home :password :shell
                                        :group :groups]))))
      :manage
      (let [mod-keys (select-keys opts [:home :shell :comment :group :groups
                                        :password :append])]
        (stevedore/script
         (if (~lib/user-exists? ~username)
           ~(if (seq mod-keys)
              (stevedore/script (~lib/modify-user ~username ~mod-keys))
              ":")
           (~lib/create-user
            ~username ~(select-keys opts [:base-dir :home :system :comment
                                          :create-home :password :shell
                                          :group :groups])))))
      :lock
      (stevedore/script
       (if (~lib/user-exists? ~username)
         (~lib/lock-user ~username)))
      :unlock
      (stevedore/script
       (if (~lib/user-exists? ~username)
         (~lib/unlock-user ~username)))
      :remove
      (stevedore/script
       (if (~lib/user-exists? ~username)
         (~lib/remove-user ~username ~(select-keys opts [:remove :force]))))
      (throw (IllegalArgumentException.
              (str action " is not a valid action for user action"))))))


(action/def-aggregated-action user
  "User management."
  [session user-args]
  {:arglists (:arglists (meta pallet.action.user/user*))
   :always-after #{`group}}
  (string/join \newline (map #(apply user* session %) user-args)))


(action/def-bash-action group
  "User Group Management."
  [session groupname & {:keys [action system gid password]
                        :or {action :manage}
                        :as options}]
  (case action
    :create
    (stevedore/script
     (if-not (~lib/group-exists? ~groupname)
       (~lib/create-group
        ~groupname ~(select-keys options [:system :gid :password]))))
    :manage
    (stevedore/script
     (if (~lib/group-exists? ~groupname)
       (~lib/modify-group
        ~groupname ~(select-keys options [:gid :password]))
       (~lib/create-group
        ~groupname ~(select-keys options [:system :gid :password]))))
    :remove
    (stevedore/script
     (if (~lib/group-exists? ~groupname)
       (~lib/remove-group ~groupname {})))
    (throw (IllegalArgumentException.
            (str action " is not a valid action for group action")))))
