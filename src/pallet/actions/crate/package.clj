(ns pallet.actions.crate.package
  "Packages action implementation"
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :refer [tracef]]
   [pallet.actions.decl
    :refer [exec-checked-script package-source-changed-flag remote-file-action]]
   [pallet.core.session :refer [packager]]
   [pallet.script.lib
    :refer [file heredoc install-package list-installed-packages
            package-manager-non-interactive purge-package os-version-name
            upgrade-package remove-package rm update-package-list]]
   [pallet.stevedore :refer [chain-commands* chained-script fragment script]]
   [pallet.version-dispatch :refer [os-map os-map-lookup]]))

;;; packages
(defmulti adjust-packages
  (fn [& _] (packager)))

;; http://algebraicthunk.net/~dburrows/projects/aptitude/doc/en/ch02s03s01.html
(def ^{:private true} aptitude-escape-map
  {\+ "\\+"
   \- "\\-"
   \. "\\."
   \( "\\"
   \) "\\)"
   \| "\\|"
   \[ "\\["
   \] "\\]"
   \^ "\\^"
   \$ "\\$"})

;; aptitude and apt can install, remove and purge all in one command, so we just
;; need to split by enable/disable options.
(defmethod adjust-packages :aptitude
  [packages]
  (tracef "adjust-packages :aptitude %s" (vec packages))
  (exec-checked-script
   "Packages"
   (package-manager-non-interactive)
   (defn enableStart [] (rm "/usr/sbin/policy-rc.d"))
   ~(chain-commands*
     (for [[opts packages] (->>
                            packages
                            (group-by #(select-keys % [:enable :allow-unsigned
                                                       :disable-service-start]))
                            (sort-by #(apply min (map :priority (second %)))))]
       (chained-script
        ~(if (:disable-service-start opts)
           (do
             (script
              (chain-and
               ("trap" enableStart EXIT)
               (heredoc "/usr/sbin/policy-rc.d" "#!/bin/sh\nexit 101" {}))))
           "")
        ("aptitude"
         install -q -y
         ~(string/join " " (map #(str "-t " %) (:enable opts)))
         ~(if (:allow-unsigned opts)
            "-o 'APT::Get::AllowUnauthenticated=true'"
            "")
         ~(string/join
           " "
           (for [[action packages] (group-by #(:action % :install) packages)
                 {:keys [package force purge]} packages]
             (case action
               :install (format "%s+" package)
               :remove (if purge
                         (format "%s_" package)
                         (format "%s-" package))
               :upgrade (format "%s+" package)
               (throw
                (IllegalArgumentException.
                 (str
                  action " is not a valid action for package action")))))))
        ~(if (:disable-service-start opts)
           (do
             (script
              (chain-and
               ("enableStart")
               ("trap" - EXIT))))
           ""))))
   ;; aptitude doesn't report failed installed in its exit code
   ;; so explicitly check for success
   ~(chain-commands*
     (for [{:keys [package action]} packages
           :let [escaped-package (string/escape package aptitude-escape-map)]]
       (cond
        (#{:install :upgrade} action)
        (script
         (pipe ("aptitude"
                search
                (quoted
                 (str "?and(?installed, ?name(^" ~escaped-package "$))")))
               ("grep" (quoted ~package))))
        (= :remove action)
        (script
         (not (pipe ("aptitude"
                     search
                     (quoted
                      (str "?and(?installed, ?name(^" ~escaped-package "$))")))
                    ("grep" (quoted ~package))))))))))

(defmethod adjust-packages :apt
  [packages]
  (exec-checked-script
   "Packages"
   (package-manager-non-interactive)
   (defn enableStart [] (rm "/usr/sbin/policy-rc.d"))
   ~(chain-commands*
     (for [[opts packages] (->>
                            packages
                            (group-by #(select-keys % [:enable :allow-unsigned
                                                       :disable-service-start]))
                            (sort-by #(apply min (map :priority (second %)))))]
       (chained-script
        ~(if (:disable-service-start opts)
           (do
             (script
              (chain-and
               ("trap" enableStart EXIT)
               (heredoc "/usr/sbin/policy-rc.d" "#!/bin/sh\nexit 101" {}))))
           "")
        ("apt-get"
         -q -y install
         ~(string/join " " (map #(str "-t " %) (:enable opts)))
         ~(if (:allow-unsigned opts) "--allow-unauthenticated" "")
         ~(string/join
           " "
           (for [[action packages] (group-by #(:action % :install) packages)
                 {:keys [package force purge]} packages]
             (case action
               :install (format "%s+" package)
               :remove (if purge
                         (format "%s_" package)
                         (format "%s-" package))
               :upgrade (format "%s+" package)
               (throw
                (IllegalArgumentException.
                 (str (pr-str action)
                      " is not a valid action for package action")))))))
        ~(if (:disable-service-start opts)
           (do
             (script
              (chain-and
               ("enableStart")
               ("trap" - EXIT))))
           ""))))
   (list-installed-packages)))

(def ^{:private true :doc "Define the order of actions"}
  action-order {:install 10 :remove 20 :upgrade 30})

;; `yum` has separate install, remove and purge commands, so we just need to
;; split by enable/disable options and by command.  We install before removing.
(defmethod adjust-packages :yum
  [packages]
  (exec-checked-script
   "Packages"
   ~(chain-commands*
     (conj
      (vec
       (for [[action packages] (->>
                                packages
                                (sort-by #(action-order (:action % :install)))
                                (group-by #(:action % :install)))
             [opts packages] (->>
                              packages
                              (group-by
                               #(select-keys % [:enable :disable :exclude]))
                              (sort-by
                               #(apply min (map :priority (second %)))))]
         (script
          ("yum"
           ~(name action) -q -y
           ~(string/join " " (map #(str "--disablerepo=" %) (:disable opts)))
           ~(string/join " " (map #(str "--enablerepo=" %) (:enable opts)))
           ~(string/join " " (map #(str "--exclude=" %) (:exclude opts)))
           ~(string/join
             " "
             (distinct (map :package packages)))))))
      (list-installed-packages)))))

(defmethod adjust-packages :default
  [packages]
  (exec-checked-script
   "Packages"
   (package-manager-non-interactive)
   ~(chain-commands*
     (for [[action packages] (group-by #(:action % :install) packages)
           {:keys [package force purge]} packages]
       (case action
         :install (script (install-package ~package :force ~force))
         :remove (if purge
                   (script (purge-package ~package))
                   (script (remove-package ~package)))
         :upgrade (script (upgrade-package ~package))
         (throw
          (IllegalArgumentException.
           (str action " is not a valid action for package action"))))))))

(defn packages
  "Install or remove packages.

   Options
    - :action [:install | :remove | :upgrade]
    - :purge [true|false]          when removing, whether to remove all config
    - :enable [repo|(seq repo)]    enable specific repository
    - :disable [repo|(seq repo)]   disable specific repository
    - :allow-unsigned [true|false] allow unsigned packages
    - :disable-service-start       disable service startup (default false)

   You probably want to use pallet.crate.package/package rather than
   this action directly, which allows package management occurs in one
   shot, so that the package manager can maintain a consistent view."
  [packages & options]
  (adjust-packages packages))

;;; package-repository

(def source-location
  {:aptitude "/etc/apt/sources.list.d/%s.list"
   :apt "/etc/apt/sources.list.d/%s.list"
   :yum "/etc/yum.repos.d/%s.repo"})

(defmulti format-source
  "Format a package source definition"
  (fn [packager & _] packager))

(defmethod format-source :aptitude
  [_ name options]
  (format
   "%s %s %s %s\n"
   (:source-type options "deb")
   (:url options)
   (:release options (fragment (os-version-name)))
   (string/join " " (:scopes options ["main"]))))

(defmethod format-source :apt
  [_ name options]
  (format-source :aptitude name options))

(defmethod format-source :yum
  [_ name {:keys [url mirrorlist gpgcheck gpgkey priority failovermethod
                  enabled]
           :or {enabled 1}
           :as options}]
  (string/join
   "\n"
   (filter
    identity
    [(format "[%s]\nname=%s" name name)
     (when url (format "baseurl=%s" url))
     (when mirrorlist (format "mirrorlist=%s" mirrorlist))
     (format "gpgcheck=%s" (or (and gpgkey 1) 0))
     (when gpgkey (format "gpgkey=%s" gpgkey))
     (when priority (format "priority=%s" priority))
     (when failovermethod (format "failovermethod=%s" failovermethod))
     (format "enabled=%s" enabled)
     ""])))

(def ^{:dynamic true} *default-apt-keyserver* "subkeys.pgp.net")

(defmulti package-repository
  "Add a package repository for the current packager.

    - :priority n                  priority (0-100, default 50)
"
  (fn  [{:keys [repository] :as options}]
    (packager)))

(def ubuntu-ppa-add
  (atom                                 ; allow for open extension
   (os-map
    {{:os :ubuntu :os-version [[10] [12 04]]} "python-software-properties"
     {:os :ubuntu :os-version [[12 10] nil]} "software-properties-common"})))

(defn- package-source-apt
  [session name {:keys [key-id key-server key-url url] :as options}]
  (let [^String key-url url]
    (if (and key-url (.startsWith key-url "ppa:"))
      (let [list-file (str
                       (string/replace (subs key-url 4) "/" "-")
                       "-" (fragment (os-version-name)) ".list")]
        (exec-checked-script
         "Add ppa"
         ~(if-let [package (os-map-lookup @ubuntu-ppa-add)]
            (script
             (chain-and
              ("apt-cache" show ~package ">" "/dev/null") ; fail if unavailable
              (install-package ~package))))
         (when (not (file-exists? (file "/etc/apt/sources.list.d" ~list-file)))
           (chain-and
            (pipe (println "") ("add-apt-repository" ~key-url))
            (update-package-list)))))
      (remote-file-action
       (format (source-location :apt) name)
       {:content (format-source :apt name options)
        :flag-on-changed package-source-changed-flag})))
  (when key-id
    (let [key-server (or key-server *default-apt-keyserver*)]
      (exec-checked-script
       session
       "Install repository key from key server"
       ("apt-key" adv "--keyserver" ~key-server "--recv-keys" ~key-id))))
  (when key-url
    (remote-file-action
     "aptkey.tmp" {:url key-url :flag-on-changed package-source-changed-flag})
    (exec-checked-script
     session
     "Install repository key"
     ("apt-key" add aptkey.tmp))))

(defmethod package-repository :aptitude
  [{:keys [repository key-id key-server key-url url] :as options}]
  (package-source-apt repository options))

(defmethod package-repository :apt
  [{:keys [repository key-id key-server key-url url] :as options}]
  (package-source-apt repository options))

(defmethod package-repository :yum
  [{:keys [repository gpgkey] :as options}]
  (remote-file-action
   (format (source-location packager) repository)
   {:content (format-source packager repository options)
    :literal true
    :flag-on-changed package-source-changed-flag})
  (when gpgkey
    (exec-checked-script
     "Install repository key"
     ("rpm" "--import" ~gpgkey))))

(defmethod package-repository :default
  [{:keys [repository] :as options}]
  (let [packager (packager)]
    (remote-file-action
     (format (source-location packager) repository)
     {:content (format-source packager repository options)
      :flag-on-changed package-source-changed-flag})))
