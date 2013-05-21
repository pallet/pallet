(ns pallet.crate.environment
  "Set up the system environment."
  (:require
   [clojure.string :as string]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.file :as file]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.script.lib :as lib]))

(defn system-environment-file
  [session env-name {:keys [path shared] :or {shared ::not-set} :as options}]
  (let [os-family (session/os-family session)
        shared (if (= shared ::not-set)
                 (not (#{:rhel :centos :fedora} os-family))
                 shared)
        path (or path (stevedore/script (~lib/system-environment)))
        path (if shared path (str path "/" env-name ".sh"))]
    [path shared]))

(defn system-environment
  "Define system wide default environment.
   On redhat based systems, this is set in /etc/profile.d, so is only
   valid within a login shell. On debian based systems, /etc/environment
   is used."
  [session env-name key-value-pairs
   & {:keys [path shared literal] :or {shared ::not-set} :as options}]
  (let [[path shared] (system-environment-file session env-name options)]
    (if shared
      (exec-script/exec-script*
       session
       (stevedore/checked-commands*
        (format "Add %s environment to %s" env-name path)
        (conj
         (for [[k v] key-value-pairs]
           (stevedore/script
            (pallet_set_env
             ~k
             ~(str
               (when literal \') (name k) "=" (pr-str v) (when literal \')))))
         (stevedore/script
          (defn pallet_set_env [k s]
            (if (grep (quoted (str "^" @k "=")) ~path)
              (sed -i (~lib/sed-ext) -e (quoted "s/^${k}=.*/${s}/") ~path)
              (sed -i (~lib/sed-ext) -e (quoted "$ a \\\\\n${s}") ~path)))))))
      (remote-file/remote-file
       session
       path
       :owner "root"
       :group "root"
       :mode 644
       :content (string/join
                 \newline
                 (for [[k v] key-value-pairs]
                   (str (name k) "=" (pr-str v))))
       :literal literal))))
