(ns pallet.crate.environment
  "Set up the system environment."
  (:require
   [clojure.string :as string]
   [pallet.action :refer [with-action-options]]
   [pallet.actions :refer [exec-script* plan-when plan-when-not remote-file]]
   [pallet.argument :refer [delayed]]
   [pallet.crate :refer [defplan os-family]]
   [pallet.action-plan :as action-plan]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]))

(defn system-environment-file
  [env-name {:keys [path shared] :or {shared ::not-set} :as options}]
  (let [os-family (os-family)
        shared (if (= shared ::not-set)
                 (not (#{:rhel :centos :fedora} os-family))
                 shared)
        path (or path (stevedore/fragment (~lib/system-environment)))
        path (if shared path (str path "/" env-name ".sh"))]
    [path shared]))

(defplan system-environment
  "Define system wide default environment.
   On redhat based systems, this is set in /etc/profile.d, so is only
   valid within a login shell. On debian based systems, /etc/environment
   is used."
  [env-name key-value-pairs & {:keys [path shared literal] :as options}]
  (let [[path shared] (system-environment-file env-name options)
        quote (if literal "'" "\"")]
    (plan-when shared
      (with-action-options {:new-login-after-action true}
        (exec-script*
         (delayed [_]
           (action-plan/checked-commands*
            (format "Add %s environment to %s" env-name path)
            (conj
             (for [[k v] key-value-pairs]
               (stevedore/script
                (var vv (str ~quote ~v ~quote)) ; v can contain multi-line
                                        ; expressions
                ("pallet_set_env"
                 (quoted ~k)
                 (quoted @vv)
                 (quoted (str ~(name k) "=\\\"" @vv "\\\"")))))
             (stevedore/script
              (if-not (file-exists? ~path)
                (lib/heredoc ~path "# environment file created by pallet\n" {}))
              (defn pallet_set_env [k v s]
                (if-not ("grep" (quoted @s) ~path "2>&-")
                  (chain-or
                   (chain-and
                    ("sed" -i (~lib/sed-ext)
                     -e (quoted "/$${k}=/ d") ~path)
                    ("sed" -i (~lib/sed-ext)
                     -e (quoted "$ a \\\\\n${s}") ~path))
                   ("exit" 1)))))))))))
    (plan-when-not shared
      (with-action-options {:new-login-after-action true}
        (remote-file
         path
         :owner "root"
         :group "root"
         :mode 644
         :content (string/join
                   \newline
                   (for [[k v] key-value-pairs]
                     (str (name k) "=" (pr-str v))))
         :literal literal)))))
