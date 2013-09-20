(ns pallet.actions.direct.package
  "Package management action.

   `package` is used to install or remove a package.

   `package-source` is used to specify a non-standard source for packages."
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.action :refer [action-fn implement-action]]
   [pallet.action-plan :refer [checked-commands]]
   [pallet.actions
    :refer [add-rpm
            debconf-set-selections
            install-deb
            minimal-packages
            package
            package-manager
            package-source
            package-source-changed-flag
            sed]]
   [pallet.actions-impl :refer [remote-file-action]]
   [pallet.core.session :refer [os-family packager]]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore :refer [checked-script fragment with-source-line-comments]]
   [pallet.utils :refer [apply-map]]
   [pallet.version-dispatch :refer [os-map os-map-lookup]]))

(require 'pallet.actions.direct.remote-file)  ; stop slamhound from removing it

(def ^{:private true}
  remote-file* (action-fn remote-file-action :direct))
(def ^{:private true}
  sed* (action-fn sed :direct))

(defmulti adjust-packages
  (fn [session & _]
    (packager session)))

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
  [session packages]
  (logging/tracef "adjust-packages :aptitude %s" (vec packages))
  (checked-commands
   "Packages"
   (stevedore/script (~lib/package-manager-non-interactive))
   (stevedore/script (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d")))
   (stevedore/chain-commands*
    (for [[opts packages] (->>
                           packages
                           (group-by #(select-keys % [:enable :allow-unsigned
                                                      :disable-service-start]))
                           (sort-by #(apply min (map :priority (second %)))))]
      (stevedore/chained-script
       ~(if (:disable-service-start opts)
          (do
            (stevedore/script
             (chain-and
              ("trap" enableStart EXIT)
              (lib/heredoc "/usr/sbin/policy-rc.d" "#!/bin/sh\nexit 101" {}))))
          "")
       ("aptitude"
        install -q -y
        ~(string/join " " (map #(str "-t " %) (:enable opts)))
        ~(if (:allow-unsigned opts)
           "-o 'APT::Get::AllowUnauthenticated=true'"
           "")
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
                 action " is not a valid action for package action")))))))
       ~(if (:disable-service-start opts)
          (do
            (stevedore/script
             (chain-and
              ("enableStart")
              ("trap" - EXIT))))
          ""))))
   ;; aptitude doesn't report failed installed in its exit code
   ;; so explicitly check for success
   (stevedore/chain-commands*
    (for [{:keys [package action]} packages
          :let [escaped-package (string/escape package aptitude-escape-map)]]
      (cond
       (#{:install :upgrade} action)
       (stevedore/script
        (pipe ("aptitude"
               search
               (quoted
                (str "?and(?installed, ?name(^" ~escaped-package "$))")))
              ("grep" (quoted ~package))))
       (= :remove action)
       (stevedore/script
        (not (pipe ("aptitude"
                    search
                    (quoted
                     (str "?and(?installed, ?name(^" ~escaped-package "$))")))
                   ("grep" (quoted ~package))))))))))

(defmethod adjust-packages :apt
  [session packages]
  (checked-commands
   "Packages"
   (stevedore/script (~lib/package-manager-non-interactive))
   (stevedore/script (defn enableStart [] (lib/rm "/usr/sbin/policy-rc.d")))
   (stevedore/chain-commands*
    (for [[opts packages] (->>
                           packages
                           (group-by #(select-keys % [:enable :allow-unsigned
                                                      :disable-service-start]))
                           (sort-by #(apply min (map :priority (second %)))))]
      (stevedore/chained-script
       ~(if (:disable-service-start opts)
          (do
            (stevedore/script
             (chain-and
              ("trap" enableStart EXIT)
              (lib/heredoc "/usr/sbin/policy-rc.d" "#!/bin/sh\nexit 101" {}))))
          "")
       ("apt-get"
        -q -y install
        ~(string/join " " (map #(str "-t " %) (:enable opts)))
        ~(if (:allow-unsigned opts) "--allow-unauthenticated" "")
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
                 action " is not a valid action for package action")))))))
       ~(if (:disable-service-start opts)
          (do
            (stevedore/script
             (chain-and
              ("enableStart")
              ("trap" - EXIT))))
          ""))))
   (stevedore/script (~lib/list-installed-packages))))

(def ^{:private true :doc "Define the order of actions"}
  action-order {:install 10 :remove 20 :upgrade 30})

;; `yum` has separate install, remove and purge commands, so we just need to
;; split by enable/disable options and by command.  We install before removing.
(defmethod adjust-packages :yum
  [session packages]
  (checked-commands
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
         ("yum"
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
  (checked-commands
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
   & {:keys [action y force purge priority enable disable allow-unsigned]
      :as options}]
  (logging/tracef "package-map %s %s" package-name options)
  (letfn [(as-seq [x] (if (or (string? x) (symbol? x) (keyword? x))
                        [(name x)] x))]
    (->
     {:action :install :y true :priority 50}
     (merge options)
     (assoc :package package-name)
     (update-in [:enable] as-seq)
     (update-in [:disable] as-seq))))

(implement-action package :direct
  "Install or remove a package.

   Options
    - :action [:install | :remove | :upgrade]
    - :purge [true|false]          when removing, whether to remove all config
    - :enable [repo|(seq repo)]    enable specific repository
    - :disable [repo|(seq repo)]   disable specific repository
    - :priority n                  priority (0-100, default 50)
    - :allow-unsigned [true|false] allow unsigned packages
    - :disable-service-start       disable service startup (default false)

   Package management occurs in one shot, so that the package manager can
   maintain a consistent view."
  {:action-type :script
   :location :target}
  [session & args]
  (logging/tracef "package %s" (vec args))
  [[{:language :bash
     :summary (str "package " (string/join " " (apply concat args)))}
    (adjust-packages
     session (map #(apply package-map session %) (distinct args)))]
   session])

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
   (:release options (with-source-line-comments false
                       (stevedore/script (~lib/os-version-name))))
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

(defmulti package-source*
  "Add a packager source."
  (fn  [session name & {:keys [apt aptitude yum] :as options}]
    (packager session)))

(def ubuntu-ppa-add
  (atom                                 ; allow for open extension
   (os-map
    {{:os :ubuntu :os-version [[10] [12 04]]} "python-software-properties"
     {:os :ubuntu :os-version [[12 10] nil]} "software-properties-common"})))

(defn- package-source-apt
  [session name & {:keys [apt aptitude yum] :as options}]
  (checked-commands
   "Package source"
   (let [^String key-url (or (:url aptitude) (:url apt))]
     (if (and key-url (.startsWith key-url "ppa:"))
       (let [list-file (str
                        (string/replace (subs key-url 4) "/" "-")
                        "-"
                        (fragment (lib/os-version-name))
                        ".list")]
         (stevedore/chain-commands
          (if-let [package (os-map-lookup @ubuntu-ppa-add)]
            (stevedore/script
             (chain-and
              ("apt-cache" show ~package ">" "/dev/null") ; fail if unavailable
              (~lib/install-package ~package))))
          (stevedore/script
           (when (not (file-exists? (lib/file "/etc/apt/sources.list.d"
                                              ~list-file)))
             (chain-and
              (pipe (println "") ("add-apt-repository" ~key-url))
              (~lib/update-package-list))))))
       (->
        (remote-file*
         session
         (format (source-location :apt) name)
         {:content (format-source
                    :apt name (or (:apt options) (:aptitude options)))
          :flag-on-changed package-source-changed-flag})
        first second)))
   (if-let [key-id (or (:key-id aptitude) (:key-id apt))]
     (let [key-server (or (:key-server aptitude) (:key-server apt)
                          *default-apt-keyserver*)]
       (stevedore/script
        ("apt-key"
         adv
         "--keyserver" ~key-server
         "--recv-keys" ~key-id))))
   (if-let [key-url (or (:key-url aptitude) (:key-url apt))]
     (stevedore/chain-commands
      (->
       (remote-file*
        session "aptkey.tmp"
        {:url key-url :flag-on-changed package-source-changed-flag})
       first second)
      (stevedore/script ("apt-key" add aptkey.tmp))))))

(defmethod package-source* :aptitude
  [session name & {:keys [apt aptitude yum] :as options}]
  (apply-map package-source-apt session name options))

(defmethod package-source* :apt
  [session name & {:keys [apt aptitude yum] :as options}]
  (apply-map package-source-apt session name options))

(defmethod package-source* :yum
  [session name & {:keys [apt aptitude yum] :as options}]
  (let [packager (packager session)]
    (checked-commands
     "Package source"
     (->
      (remote-file*
       session
       (format (source-location packager) name)
       {:content (format-source packager name (packager options))
        :literal true
        :flag-on-changed package-source-changed-flag})
      first second)
     (when-let [key (and (= packager :yum) (:gpgkey yum))]
       (stevedore/script ("rpm" "--import" ~key))))))

(defmethod package-source* :default
  [session name & {:as options}]
  (let [packager (packager session)]
    (checked-commands
     "Package source"
     (->
      (remote-file*
       session
       (format (source-location packager) name)
       {:content (format-source packager name (packager options))
        :flag-on-changed package-source-changed-flag})
      first second))))

(implement-action package-source :direct
  "Control package sources.
   Options are the package manager keywords, each specifying a map of
   packager specific options.

   :aptitude
     - :source-type string   - source type (deb)
     - :url url              - repository url
     - :scopes seq           - scopes to enable for repository
     - :key-url url          - url for key
     - :key-id id            - id for key to look it up from keyserver
     - :key-server           - the hostname of the key server to lookup keys

   :yum
     - :name                 - repository name
     - :url url          - repository base url
     - :gpgkey url           - gpg key url for repository

   Example
       (package-source \"Partner\"
         :aptitude {:url \"http://archive.canonical.com/\"
                    :scopes [\"partner\"]})"
  {:action-type :script :location :target}
  [session & args]
  [[{:language :bash
     :summary (str "package-source " (string/join " " (map vec args)))}
    (stevedore/do-script*
     (map (fn [x] (apply package-source* session x)) args))]
   session])

(defn add-scope*
  "Add a scope to all the existing package sources. Aptitude specific."
  [type scope file]
  (stevedore/chained-script
   (var tmpfile @("mktemp" -t addscopeXXXX))
   (~lib/cp ~file @tmpfile :preserve true)
   ("awk" "'{if ($1 ~" ~(str "/^" type "/") "&& !" ~(str "/" scope "/")
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

(defmethod package-manager-option [:apt :proxy]
  [session packager proxy proxy-url]
  (package-manager-option session :aptitude proxy proxy-url))

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
  (->
   (remote-file*
    session
    (format "/etc/apt/apt.conf.d/%spallet" priority)
    {:content (string/join
               \newline
               (map
                #(package-manager-option session packager (key %) (val %))
                (dissoc options :priority)))
     :literal true})
   first second))

(defmethod configure-package-manager :apt
  [session packager {:as options}]
  (configure-package-manager session :aptitude options))

(defmethod configure-package-manager :yum
  [session packager {:keys [proxy] :as options}]
  (stevedore/chain-commands
   (->
    (remote-file*
     session
     "/etc/yum.pallet.conf"
     {:content (string/join
                \newline
                (map
                 #(package-manager-option session packager (key %) (val %))
                 (dissoc options :priority)))
      :literal true})
    first second)
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
   (->
    (remote-file*
     session
     "/etc/pacman.pallet.conf"
     {:content (string/join
                \newline
                (map
                 #(package-manager-option session packager (key %) (val %))
                 (dissoc options :priority)))
      :literal true})
    first second)
   ;; include pacman.pallet.conf from pacman.conf
   (stevedore/script
    (if (not @("fgrep" "pacman.pallet.conf" "/etc/pacman.conf"))
      (do
        ~(-> (sed*
              session
              "/etc/pacman.conf"
              "a Include = /etc/pacman.pallet.conf"
              :restriction "/\\[options\\]/")
             first second))))))

(defmethod configure-package-manager :default
  [session packager {:as options}]
  (comment "do nothing"))

(defn package-manager*
  "Package management."
  [session action & options]
  (let [packager (packager session)]
    (checked-commands
     (format "package-manager %s %s" (name action) (string/join " " options))
     (case action
       :update (stevedore/script (apply ~lib/update-package-list ~options))
       :upgrade (stevedore/script (~lib/upgrade-all-packages))
       :list-installed (stevedore/script (~lib/list-installed-packages))
       :add-scope (add-scope (apply hash-map options))
       :multiverse (add-scope (apply hash-map :scope "multiverse" options))
       :universe (add-scope (apply hash-map :scope "universe" options))
       :debconf (if (#{:aptitude :apt} packager)
                  (stevedore/script
                   (apply ~lib/debconf-set-selections ~options)))
       :configure (configure-package-manager session packager options)
       (throw (IllegalArgumentException.
               (str action
                    " is not a valid action for package-manager action")))))))

(implement-action package-manager :direct
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
  {:action-type :script :location :target}
  [session & package-manager-args]
  (logging/tracef "package-manager-args %s" (vec package-manager-args))
  [[{:language :bash
     :summary (str "package-manager "
                   (string/join " " (distinct (map vec package-manager-args))))}
    (stevedore/do-script*
     (map #(apply package-manager* session %) (distinct package-manager-args)))]
   session])

(implement-action add-rpm :direct
  "Add an rpm.  Source options are as for remote file."
  {:action-type :script :location :target}
  [session rpm-name & {:as options}]
  [[{:language :bash}
    (stevedore/do-script
     (-> (remote-file*
          session rpm-name
          (merge
           {:install-new-files pallet.actions-impl/*install-new-files*
            :overwrite-changes pallet.actions-impl/*force-overwrite*}
           options))
         first second)
     (checked-script
      (format "Install rpm %s" rpm-name)
      (if-not ("rpm" -q @("rpm" -pq ~rpm-name) > "/dev/null" "2>&1")
        (do ("rpm" -U --quiet ~rpm-name)))))]
   session])

(implement-action install-deb :direct
  "Install a deb file.  Source options are as for remote file."
  {:action-type :script :location :target}
  [session deb-name & {:as options}]
  [[{:language :bash}
    (stevedore/do-script
     (-> (remote-file*
          session deb-name
          (merge
           {:install-new-files pallet.actions-impl/*install-new-files*
            :overwrite-changes pallet.actions-impl/*force-overwrite*}
           options))
         first second)
     (checked-script
      (format "Install deb %s" deb-name)
      ("dpkg" -i --skip-same-version ~deb-name)))]
   session])

(implement-action debconf-set-selections :direct
  "Set debconf selections.
Specify :line, or the other options."
  {:action-type :script :location :target}
  [session {:keys [line package question type value]}]
  {:pre [(or line (and package question type (not (nil? value))))]}
  [[{:language :bash}
    (stevedore/do-script
     (checked-script
      (format "Preseed %s"
              (or line (string/join " " [package question type value])))
      (pipe
       (println
        (quoted ~@(if line
                    [line]
                    [(name package) question (name type) (pr-str value)])))
       ("/usr/bin/debconf-set-selections"))))]
   session])

(implement-action minimal-packages :direct
  "Add minimal packages for pallet to function"
  {:action-type :script :location :target}
  [session]
  (let [os-family (os-family session)]
    [[{:language :bash}
      (cond
        (#{:ubuntu :debian} os-family) (checked-script
                                        "Add minimal packages"
                                        (~lib/update-package-list)
                                        (~lib/install-package "coreutils")
                                        (~lib/install-package "sudo"))
        (= :arch os-family) (checked-script
                             "Add minimal packages"
                             ("{" (chain-or pacman-db-upgrade true) "; } "
                              "2> /dev/null")
                             (~lib/update-package-list)
                             (~lib/upgrade-package "pacman")
                             (println "  checking for pacman-db-upgrade")
                             ("{" (chain-or (chain-and
                                             pacman-db-upgrade
                                             (~lib/update-package-list))
                                            true) "; } "
                              "2> /dev/null")
                             (~lib/install-package "sudo")))]
     session]))
