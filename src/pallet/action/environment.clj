(ns pallet.action.environment
  "Set up the system environment."
  (:require
   [clojure.string :as string]
   [pallet.action-plan :as action-plan]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.remote-file :as remote-file]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]))

(defn system-environment
  "Define system wide default environment.
   On redhat based systems, this is set in /etc/profile.d, so is only
   valid within a login shell. On debian based systems, /etc/environment
   is used."
  [session env-name key-value-pairs & {:keys [path shared] :as options}]
  (let [os-family (session/os-family session)
        [path shared] (if (and path shared)
                        [path shared]
                        (if (#{:rhel :centos :fedora} os-family)
                          ["/etc/profile.d/java.sh" false]
                          ["/etc/environment" true]))]
    (if shared
      (exec-script/exec-script*
       session
       (action-plan/checked-commands*
        (format "Add %s environment to %s" env-name path)
        (conj
         (for [[k v] key-value-pairs]
           (stevedore/script
            (pallet_set_env
             ~k ~v
             ~(str (name k) "=" (pr-str v)))))
         (stevedore/script
          (defn pallet_set_env [k v s]
            (if (not @(grep (quoted @s) ~path))
              (sed -i
                   -e (quoted "/${k}/ d")
                   -e (quoted "$ a \\\\\n${s}")
                   ~path)))))))
      (remote-file/remote-file
       session
       path
       :owner "root"
       :group "root"
       :mode 644
       :content (string/join
                 \newline
                 (for [[k v] key-value-pairs]
                   (str (name k) "=" (pr-str v))))))))
