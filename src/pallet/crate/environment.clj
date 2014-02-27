(ns pallet.crate.environment
  "Set up the system environment."
  (:require
   [clojure.string :as string]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions :refer [exec-script* remote-file]]
   [pallet.actions.decl :refer [checked-commands*]]
   [pallet.plan :refer [defplan]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.target :refer [os-family]]))

(defn system-environment-file
  [session env-name {:keys [path shared] :or {shared ::not-set} :as options}]
  (let [os-family (os-family session)
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
  [session env-name key-value-pairs & {:keys [path shared literal] :as options}]
  (let [[path shared] (system-environment-file session env-name options)
        quote (if literal "'" "\"")]
    (when shared
      (with-action-options session {:new-login-after-action true}
        (exec-script*
         session
         (checked-commands*
          (format "Add %s environment to %s" env-name path)
          (conj
           (for [[k v] key-value-pairs]
             (stevedore/chained-script
              (var vv (str ~quote ~v ~quote)) ; v can contain multi-line
                                        ; expressions
              ("pallet_set_env"
               (quoted ~k)
               (quoted @vv)
               (quoted (str ~(name k) "=\\\"" @vv "\\\"")))))
           (stevedore/chained-script
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
                 ("exit" 1))))))))))
    (when-not shared
      (with-action-options session {:new-login-after-action true}
        (remote-file
         session
         path
         {:owner "root"
          :group "root"
          :mode 644
          :content (string/join
                    \newline
                    (for [[k v] key-value-pairs]
                      (str (name k) "=" (pr-str v))))
          :literal literal})))))
