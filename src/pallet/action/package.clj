(ns pallet.action.package
  "Package management action.

   `package` is used to install or remove a package.

   `package-source` is used to specify a non-standard source for packages."
  (:require
   [pallet.action :as action]
   [pallet.action.file :as file]
   [pallet.action.remote-file :as remote-file]
   [pallet.action.exec-script :as exec-script]
   [pallet.action.exec-script :as exec-script]
   [pallet.parameter :as parameter]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.stevedore :as stevedore]
   [pallet.script :as script]
   [pallet.utils :as utils]
   [clojure.string :as string])
  (:use
   pallet.thread-expr))

(def ^{:private true}
  remote-file* (action/action-fn remote-file/remote-file-action))
(def ^{:private true}
  sed* (action/action-fn file/sed))

(defmulti adjust-packages
  (fn [session & _]
    (session/packager session)))

;; aptitude can install, remove and purge all in one command, so we just need to
;; split by enable/disable options.
(defmethod adjust-packages :aptitude
  [session packages]
  (stevedore/checked-commands
   "Packages"
   (stevedore/script (~lib/package-manager-non-interactive))
   (stevedore/chain-commands*
    (for [[opts packages] (->>
                           packages
                           (group-by #(select-keys % [:enable]))
                           (sort-by #(apply min (map :priority (second %)))))]
      (stevedore/script
       (aptitude
        install -q -y
        ~(string/join " " (map #(str "-t " %) (:enable opts)))
        ~(string/join
          " "
          (for [[action packages] (group-by :action packages)
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
                 action " is not a valid action for package action"))))))))))
   (stevedore/script (~lib/list-installed-packages))))

(def ^{:private true :doc "Define the order of actions"}
  action-order {:install 10 :remove 20 :upgrade 30})

;; `yum` has separate install, remove and purge commands, so we just need to
;; split by enable/disable options and by command.  We install before removing.
(defmethod adjust-packages :yum
  [session packages]
  (stevedore/checked-commands
   "Packages"
   (stevedore/chain-commands*
    (conj
     (vec
      (for [[action packages] (->> packages
                                   (sort-by #(action-order (:action %)))
                                   (group-by :action))
            [opts packages] (->>
                             packages
                             (group-by
                              #(select-keys % [:enable :disable :exclude]))
                             (sort-by #(apply min (map :priority (second %)))))]
        (stevedore/script
         (yum
          ~(name action) -q -y
          ~(string/join " " (map #(str "--disablerepo=" %) (:disable opts)))
          ~(string/join " " (map #(str "--enablerepo=" %) (:enable opts)))
          ~(string/join " " (map #(str "--exclude=" %) (:exclude opts)))
          ~(string/join
            " "
            (distinct (map :package packages)))))))
     (stevedore/script (~lib/list-installed-packages))))))


(defmethod adjust-packages :default
  [session packages]
  (stevedore/checked-commands
   "Packages"
   (stevedore/chain-commands*
    (list*
     (stevedore/script (~lib/package-manager-non-interactive))
     (for [[action packages] (group-by :action packages)
           {:keys [package force purge]} packages]
       (case action
         :install (stevedore/script
                   (~lib/install-package ~package :force ~force))
         :remove (if purge
                   (stevedore/script (~lib/purge-package ~package))
                   (stevedore/script (~lib/remove-package ~package)))
         :upgrade (stevedore/script (~lib/upgrade-package ~package))
         (throw
          (IllegalArgumentException.
           (str action " is not a valid action for package action")))))))))

(defn- package-map
  "Convert the args into a single map"
  [session package-name
   & {:keys [action y force purge priority enable disable] :as options}]
  (letfn [(as-seq [x] (if (or (string? x) (symbol? x) (keyword? x))
                        [(name x)] x))]
    (->
     {:action :install :y true :priority 50}
     (merge options)
     (assoc :package package-name)
     (update-in [:enable] as-seq)
     (update-in [:disable] as-seq))))

(action/def-aggregated-action package
  "Install or remove a package.

   Options
    - :action [:install | :remove | :upgrade]
    - :purge [true|false]         when removing, whether to remove all config
    - :enable [repo|(seq repo)]   enable specific repository
    - :disable [repo|(seq repo)]  disable specific repository
    - :priority n                 priority (0-100, default 50)

   Package management occurs in one shot, so that the package manager can
   maintain a consistent view."
  [session args]
  {:arglists '([session package-name
                & {:keys [action y force purge enable disable priority]
                   :or {action :install
                        y true
                        priority 50}
                   :as options}])}
  (adjust-packages session (map #(apply package-map session %) args)))

(defn packages
  "Install a list of packages keyed on packager.
       (packages session
         :yum [\"git\" \"git-email\"]
         :aptitude [\"git-core\" \"git-email\"])"
  [session & {:keys [yum aptitude pacman brew] :as options}]
  (->
   session
   (for->
    [package-name (options (session/packager session))]
    (package package-name))))

(def source-location
  {:aptitude "/etc/apt/sources.list.d/%s.list"
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
   (:release options (stevedore/script (~lib/os-version-name)))
   (string/join " " (:scopes options ["main"]))))

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

(defn package-source*
  "Add a packager source."
  [session name & {:as options}]
  (let [packager (session/packager session)]
    (stevedore/checked-commands
     "Package source"
     (let [key-url (-> options :aptitude :url)]
       (if (and key-url (.startsWith key-url "ppa:"))
         (stevedore/chain-commands
          (stevedore/script (~lib/install-package "python-software-properties"))
          (stevedore/script (add-apt-repository ~key-url)))
         (remote-file*
          session
          (format (source-location packager) name)
          :content (format-source packager name (packager options))
          :literal (= packager :yum))))
     (if (and (-> options :aptitude :key-id)
              (= packager :aptitude))
       (stevedore/script
        (apt-key adv
                 "--keyserver subkeys.pgp.net --recv-keys"
                 ~(-> options :aptitude :key-id))))
     (if (and (-> options :aptitude :key-url)
              (= packager :aptitude))
       (stevedore/chain-commands
        (remote-file*
         session
         "aptkey.tmp"
         :url (-> options :aptitude :key-url))
        (stevedore/script (apt-key add aptkey.tmp))))
     (when-let [key (and (= packager :yum) (-> options :yum :gpgkey))]
       (stevedore/script (rpm "--import" ~key))))))

(declare package-manager)

(action/def-aggregated-action package-source
  "Control package sources.
   Options are the package manager keywords, each specifying a map of
   packager specific options.

   :aptitude
     - :source-type string   - source type (deb)
     - :url url              - repository url
     - :scopes seq           - scopes to enable for repository
     - :key-url url          - url for key
     - :key-id id            - id for key to look it up from keyserver

   :yum
     - :name                 - repository name
     - :url url          - repository base url
     - :gpgkey url           - gpg key url for repository

   Example
       (package-source \"Partner\"
         :aptitude {:url \"http://archive.canonical.com/\"
                    :scopes [\"partner\"]})"
  [session args]
  {:arglists (:arglists (meta pallet.action.package/package-source*))
   :always-before #{`package-manager `package}}
  (stevedore/do-script*
   (map (fn [x] (apply package-source* session x)) args)))

(defn add-scope*
  "Add a scope to all the existing package sources. Aptitude specific."
  [type scope file]
  (stevedore/chained-script
   (var tmpfile @(mktemp -t addscopeXXXX))
   (~lib/cp ~file @tmpfile :preserve true)
   (awk "'{if ($1 ~" ~(str "/^" type "/") "&& !" ~(str "/" scope "/")
        " ) print $0 \" \" \"" ~scope  "\" ; else print; }'"
        ~file > @tmpfile)
   (~lib/mv @tmpfile ~file :force ~true)))

(defn add-scope
  "Add a scope to an apt source"
  [opts]
  (add-scope*
   (or (opts :type) "deb.*")
   (:scope opts)
   (or (opts :file) "/etc/apt/sources.list")))

(defmulti configure-package-manager
  "Configure the package manager"
  (fn [session packager options] packager))

(defmulti package-manager-option
  "Provide packager specific options"
  (fn [session packager option value] [packager option]))

(defmethod package-manager-option [:aptitude :proxy]
  [session packager proxy proxy-url]
  (format "ACQUIRE::http::proxy \"%s\";" proxy-url))

(defmethod package-manager-option [:yum :proxy]
  [session packager proxy proxy-url]
  (format "proxy=%s" proxy-url))

(defmethod package-manager-option [:pacman :proxy]
  [session packager proxy proxy-url]
  (format
   (str "XferCommand = /usr/bin/wget "
        "-e \"http_proxy = %s\" -e \"ftp_proxy = %s\" "
        "--passive-ftp --no-verbose -c -O %%o %%u")
   proxy-url proxy-url))

(def default-installonlypkgs
  (str "kernel kernel-smp kernel-bigmem kernel-enterprise kernel-debug "
       "kernel-unsupported"))

(defmethod package-manager-option [:yum :installonlypkgs]
  [session packager installonly packages]
  (format
   "installonlypkgs=%s %s" (string/join " " packages) default-installonlypkgs))

(defmethod configure-package-manager :aptitude
  [session packager {:keys [priority prox] :or {priority 50} :as options}]
  (remote-file*
   session
   (format "/etc/apt/apt.conf.d/%spallet" priority)
   :content (string/join
             \newline
             (map
              #(package-manager-option session packager (key %) (val %))
              (dissoc options :priority)))
   :literal true))

(defmethod configure-package-manager :yum
  [session packager {:keys [proxy] :as options}]
  (stevedore/chain-commands
   (remote-file*
    session
    "/etc/yum.pallet.conf"
    :content (string/join
              \newline
              (map
               #(package-manager-option session packager (key %) (val %))
               (dissoc options :priority)))
    :literal true)
   ;; include yum.pallet.conf from yum.conf
   (stevedore/script
    (if (not @("fgrep" "yum.pallet.conf" "/etc/yum.conf"))
      (do
        ("cat" ">>" "/etc/yum.conf" " <<'EOFpallet'")
        "include=file:///etc/yum.pallet.conf"
        "EOFpallet")))))

(defmethod configure-package-manager :pacman
  [session packager {:keys [proxy] :as options}]
  (stevedore/chain-commands
   (remote-file*
    session
    "/etc/pacman.pallet.conf"
    :content (string/join
              \newline
              (map
               #(package-manager-option session packager (key %) (val %))
               (dissoc options :priority)))
    :literal true)
   ;; include pacman.pallet.conf from pacman.conf
   (stevedore/script
    (if (not @("fgrep" "pacman.pallet.conf" "/etc/pacman.conf"))
      (do
        ~(sed*
          session
          "/etc/pacman.conf"
          "a Include = /etc/pacman.pallet.conf"
          :restriction "/\\[options\\]/"))))))

(defmethod configure-package-manager :default
  [session packager {:as options}]
  (comment "do nothing"))

(defn package-manager*
  "Package management."
  [session action & options]
  (let [packager (session/packager session)]
    (stevedore/checked-commands
     (format "package-manager %s %s" (name action) (string/join " " options))
     (case action
       :update (stevedore/script (apply ~lib/update-package-list ~options))
       :upgrade (stevedore/script (~lib/upgrade-all-packages))
       :list-installed (stevedore/script (~lib/list-installed-packages))
       :add-scope (add-scope (apply hash-map options))
       :multiverse (add-scope (apply hash-map :scope "multiverse" options))
       :universe (add-scope (apply hash-map :scope "universe" options))
       :debconf (if (= :aptitude packager)
                  (stevedore/script
                   (apply ~lib/debconf-set-selections ~options)))
       :configure (configure-package-manager session packager options)
       (throw (IllegalArgumentException.
               (str action
                    " is not a valid action for package-manager action")))))))

(action/def-aggregated-action package-manager
  "Package manager controls.

   `action` is one of the following:
   - :update          - update the list of available packages
   - :list-installed  - output a list of the installed packages
   - :add-scope       - enable a scope (eg. multiverse, non-free)

   To refresh the list of packages known to the pakage manager:
       (package-manager session :update)

   To enable multiverse on ubuntu:
       (package-manager session :add-scope :scope :multiverse)

   To enable non-free on debian:
       (package-manager session :add-scope :scope :non-free)"
  [session package-manager-args]
  {:copy-arglist (:arglists (meta pallet.action.package/package-manager*))
   :always-before `package}
  (stevedore/do-script*
   (map #(apply package-manager* session %) (distinct package-manager-args))))

(action/def-bash-action add-rpm
  "Add an rpm.  Source options are as for remote file."
  [request rpm-name & {:as options}]
  (stevedore/do-script
   (apply remote-file* request rpm-name (apply concat options))
   (stevedore/checked-script
    (format "Install rpm %s" rpm-name)
    (if-not (rpm -q @(rpm -pq ~rpm-name) > "/dev/null" "2>&1")
      (do (rpm -U --quiet ~rpm-name))))))

(action/def-bash-action minimal-packages
  "Add minimal packages for pallet to function"
  [session]
  {:always-before #{`package-manager `package-source `package}}
  (let [os-family (session/os-family session)]
    (cond
     (#{:ubuntu :debian} os-family) (stevedore/checked-script
                                     "Add minimal packages"
                                     (~lib/update-package-list)
                                     (~lib/install-package "coreutils")
                                     (~lib/install-package "sudo"))
     (= :arch os-family) (stevedore/checked-script
                          "Add minimal packages"
                          ("{" pacman-db-upgrade "||" true "; } "
                           "2> /dev/null")
                          (~lib/update-package-list)
                          (~lib/upgrade-package "pacman")
                          (println "  checking for pacman-db-upgrade")
                          ("{" pacman-db-upgrade
                           "&&" (~lib/update-package-list)
                           "||" true "; } "
                           "2> /dev/null")
                          (~lib/install-package "sudo")))))
