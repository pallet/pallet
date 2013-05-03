(ns pallet.crate.environment
  "Set up the system environment."
  (:require
   [clojure.string :as string]
   [pallet.action :refer [with-action-options]]
   [pallet.actions :refer [exec-script* plan-when plan-when-not remote-file]]
   [pallet.crate :refer [defplan os-family]]
   [pallet.stevedore :as action-plan]
   [pallet.stevedore :as stevedore]))

(defplan system-environment
  "Define system wide default environment.
   On redhat based systems, this is set in /etc/profile.d, so is only
   valid within a login shell. On debian based systems, /etc/environment
   is used."
  [env-name key-value-pairs & {:keys [path shared] :as options}]
  (let [os-family (os-family)
        [path shared] (if (and path shared)
                        [path shared]
                        (if (#{:rhel :centos :fedora} os-family)
                          ["/etc/profile.d/java.sh" false]
                          ["/etc/environment" true]))]
    (plan-when shared
      (with-action-options {:new-login-after-action true}
        (exec-script*
         (action-plan/checked-commands*
          (format "Add %s environment to %s" env-name path)
          (conj
           (for [[k v] key-value-pairs]
             (stevedore/script
              (var vv ~v)              ; so v can contain multi-line expressions
              ("pallet_set_env" ~k @vv (str ~(name k) "=" (quoted @vv)))))
           (stevedore/script
            (defn pallet_set_env [k v s]
              (if (not @("grep" (quoted @s) ~path))
                (do
                  (chain-or
                   (chain-and
                    ("sed" -i -e (quoted "/${k}/ d") ~path)
                    ("sed" -i -e (quoted "$ a \\\\\n${s}") ~path))
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
                     (str (name k) "=" (pr-str v)))))))))
