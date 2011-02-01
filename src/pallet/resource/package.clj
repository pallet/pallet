(ns pallet.resource.package
  "Package management resource.

   `package` is used to install or remove a package.

   `package-source` is used to specify a non-standard source for packages."
  (:require
   [pallet.resource :as resource]
   [pallet.resource.file :as file]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.hostinfo :as hostinfo]
   [pallet.resource.exec-script :as exec-script]
   pallet.resource.script
   [pallet.stevedore :as stevedore]
   [pallet.stevedore.script :as script-impl]
   [pallet.request-map :as request-map]
   [pallet.script :as script]
   [pallet.utils :as utils]
   [pallet.target :as target]
   [clojure.contrib.string :as string]
   [clojure.contrib.logging :as logging])
  (:use
   [pallet.resource :only [defaggregate defresource]]
   [clojure.contrib.core :only [-?>]]
   pallet.thread-expr))

;;; the package management commands vary for each distribution, so we
;;; use a script multimethod to describe these
(script/defscript update-package-list
  "Update the list of packages available to the package manager from the
   declared package sources."
  [& options])

(script/defscript upgrade-all-packages
  "Upgrade the all installed package."
  [& options])

(script/defscript install-package
  "Install the specified package."
  [name & options])

(script/defscript upgrade-package
  "Upgrade the specified package."
  [name & options])

(script/defscript remove-package
  "Uninstall the specified package, leaving the configuration files if
   possible."
  [name & options])

(script/defscript purge-package
  "Uninstall the specified package, removing the configuration files if
   possible."
  [name & options])

(script/defscript list-installed-packages
  "List the installed packages"
  [& options])

;;; Implementation to do nothing
;;; Repeating the selector makes it more explicit
(script-impl/defimpl update-package-list [#{:no-packages} #{:no-packages}]
  [& options] "")
(script-impl/defimpl upgrade-all-packages [#{:no-packages} #{:no-packages}]
  [& options] "")
(script-impl/defimpl install-package [#{:no-packages} #{:no-packages}]
  [package & options] "")
(script-impl/defimpl upgrade-package [#{:no-packages} #{:no-packages}]
  [package & options] "")
(script-impl/defimpl remove-package [#{:no-packages} #{:no-packages}]
  [package & options] "")
(script-impl/defimpl purge-package [#{:no-packages} #{:no-packages}]
  [package & options] "")
(script-impl/defimpl list-installed-packages [#{:no-packages} #{:no-packages}]
  [& options] "")

;;; aptitude
(script-impl/defimpl update-package-list [#{:aptitude}] [& options]
  (chain-or
   ("aptitude" update ~(stevedore/option-args options)) true))

(script-impl/defimpl upgrade-all-packages [#{:aptitude}] [& options]
  ("aptitude" upgrade -q -y ~(stevedore/option-args options)))

(script-impl/defimpl install-package [#{:aptitude}] [package & options]
  ("aptitude" install -q -y ~(stevedore/option-args options) ~package
            ;; show returns an error code if no package found, while install
            ;; does not.  There should be a better way than this...
            "&&" aptitude show ~package))

(script-impl/defimpl upgrade-package [#{:aptitude}] [package & options]
  ("aptitude" install -q -y ~(stevedore/option-args options) ~package
            ;; show returns an error code if no package found, while install
            ;; does not.  There should be a better way than this...
            "&&" aptitude show ~package))

(script-impl/defimpl remove-package [#{:aptitude}] [package & options]
  ("aptitude" remove -y ~(stevedore/option-args options) ~package))

(script-impl/defimpl purge-package [#{:aptitude}] [package & options]
  ("aptitude" purge -y  ~(stevedore/option-args options) ~package))

(script-impl/defimpl list-installed-packages [#{:aptitude}] [& options]
  ("aptitude" search (quoted "~i")))

;;; yum
(script-impl/defimpl update-package-list [#{:yum}] [& options]
  ("yum" makecache -q ~(stevedore/option-args options)))

(script-impl/defimpl upgrade-all-packages [#{:yum}] [& options]
  ("yum" update -y -q ~(stevedore/option-args options)))

(script-impl/defimpl install-package [#{:yum}] [package & options]
  ("yum" install -y -q ~(stevedore/option-args options) ~package))

(script-impl/defimpl upgrade-package [#{:yum}] [package & options]
  ("yum" upgrade -y -q ~(stevedore/option-args options) ~package))

(script-impl/defimpl remove-package [#{:yum}] [package & options]
  ("yum" remove ~(stevedore/option-args options) ~package))

(script-impl/defimpl purge-package [#{:yum}] [package & options]
  ("yum" purge ~(stevedore/option-args options) ~package))

(script-impl/defimpl list-installed-packages [#{:yum}] [& options]
  ("yum" list installed))

;;; zypper
(script-impl/defimpl update-package-list [#{:zypper}] [& options]
  ("zypper" refresh ~(stevedore/option-args options)))

(script-impl/defimpl upgrade-all-packages [#{:zypper}] [& options]
  ("zypper" update -y ~(stevedore/option-args options)))

(script-impl/defimpl install-package [#{:zypper}] [package & options]
  ("zypper" install -y ~(stevedore/option-args options) ~package))

(script-impl/defimpl remove-package [#{:zypper}] [package & options]
  ("zypper" remove ~(stevedore/option-args options) ~package))

(script-impl/defimpl purge-package [#{:zypper}] [package & options]
  ("zypper" remove ~(stevedore/option-args options) ~package))

;;; pacman
(script-impl/defimpl update-package-list [#{:pacman}] [& options]
  ("pacman" -Sy "--noconfirm" "--noprogressbar"
   ~(stevedore/option-args options)))

(script-impl/defimpl upgrade-all-packages [#{:pacman}] [& options]
  ("pacman" -Su "--noconfirm" "--noprogressbar" ~(stevedore/option-args options)))

(script-impl/defimpl install-package [#{:pacman}] [package & options]
  ("pacman" -S "--noconfirm" "--noprogressbar"
   ~(stevedore/option-args options) ~package))

(script-impl/defimpl upgrade-package [#{:pacman}] [package & options]
  ("pacman" -S "--noconfirm" "--noprogressbar"
   ~(stevedore/option-args options) ~package))

(script-impl/defimpl remove-package [#{:pacman}] [package & options]
  ("pacman" -R "--noconfirm" ~(stevedore/option-args options) ~package))

(script-impl/defimpl purge-package [#{:pacman}] [package & options]
  ("pacman" -R "--noconfirm" "--nosave"
   ~(stevedore/option-args options) ~package))

;; brew
(script-impl/defimpl update-package-list [#{:brew}] [& options]
  ("brew" update ~(stevedore/option-args options)))

(script-impl/defimpl upgrade-all-packages [#{:brew}] [& options]
  (comment "No command to do this"))

(script-impl/defimpl install-package [#{:brew}] [package & options]
  ("brew" install -y ~(stevedore/option-args options) ~package))

(script-impl/defimpl remove-package [#{:brew}] [package & options]
  ("brew" uninstall ~(stevedore/option-args options) ~package))

(script-impl/defimpl purge-package [#{:brew}] [package & options]
  ("brew" uninstall ~(stevedore/option-args options) ~package))


(script/defscript debconf-set-selections [& selections])
(script-impl/defimpl debconf-set-selections :default [& selections] "")
(script-impl/defimpl debconf-set-selections [#{:aptitude}] [& selections]
  ("{ debconf-set-selections"
   ~(str "<<EOF\n" (string/join \newline selections) "\nEOF\n}")))

(script/defscript package-manager-non-interactive [])
(script-impl/defimpl package-manager-non-interactive :default [] "")
(script-impl/defimpl package-manager-non-interactive [#{:aptitude}] []
  (debconf-set-selections
   "debconf debconf/frontend select noninteractive"
   "debconf debconf/frontend seen false"))

(defmulti adjust-packages
  (fn [request & _]
    (:target-packager request)))

;; aptitude can install, remove and purge all in one command, so we just need to
;; split by enable/disable options.
(defmethod adjust-packages :aptitude
  [request packages]
  (stevedore/checked-commands
   "Packages"
   (stevedore/script (package-manager-non-interactive))
   (stevedore/chain-commands*
    (for [[opts packages] (->>
                           packages
                           (group-by #(select-keys % [:enable]))
                           (sort-by #(apply min (map :priority (second %)))))]
      (stevedore/script
       ("aptitude"
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
                 action " is not a valid action for package resource"))))))))))
   (stevedore/script (list-installed-packages))))

(def ^{:private true :doc "Define the order of actions"}
  action-order {:install 10 :remove 20 :upgrade 30})

;; `yum` has separate install, remove and purge commands, so we just need to
;; split by enable/disable options and by command.  We install before removing.
(defmethod adjust-packages :yum
  [request packages]
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
                             (group-by #(select-keys % [:enable :disable]))
                             (sort-by #(apply min (map :priority (second %)))))]
        (stevedore/script
         ("yum"
          ~(name action) -q -y
          ~(string/join " " (map #(str "--enablerepo=" %) (:enable opts)))
          ~(string/join " " (map #(str "--disablerepo=" %) (:disable opts)))
          ~(string/join
            " "
            (distinct (map :package packages)))))))
     (stevedore/script (list-installed-packages))))))


(defmethod adjust-packages :default
  [request packages]
  (stevedore/checked-commands
   "Packages"
   (stevedore/chain-commands*
    (list*
     (stevedore/script (package-manager-non-interactive))
     (for [[action packages] (group-by :action packages)
           {:keys [package force purge]} packages]
       (case action
         :install (stevedore/script
                   (install-package ~package :force ~force))
         :remove (if purge
                   (stevedore/script (purge-package ~package))
                   (stevedore/script (remove-package ~package)))
         :upgrade (stevedore/script (upgrade-package ~package))
         (throw
          (IllegalArgumentException.
           (str action " is not a valid action for package resource")))))))))

(defn- package-map
  "Convert the args into a single map"
  [request package-name
   & {:keys [action y force purge priority enable disable] :as options}]
  (letfn [(as-seq [x] (if (or (string? x) (symbol? x) (keyword? x))
                        [(name x)] x))]
    (->
     {:action :install :y true :priority 50}
     (merge options)
     (assoc :package package-name)
     (update-in [:enable] as-seq)
     (update-in [:disable] as-seq))))

(defaggregate package
  "Install or remove a package.

   Options
    - :action [:install | :remove | :upgrade]
    - :purge [true|false]         when removing, whether to remove all config
    - :enable [repo|(seq repo)]   enable specific repository
    - :disable [repo|(seq repo)]  disable specific repository
    - :priority n                 priority (0-100, default 50)

   Package management occurs in one shot, so that the package manager can
   maintain a consistent view."
  {:use-arglist  [request package-name
                  & {:keys [action y force purge enable disable priority]
                     :or {action :install
                          y true
                          priority 50}
                     :as options}]}
  (package*
   [request args]
   (adjust-packages request (map #(apply package-map request %) args))))

(defn packages
  "Install a list of packages keyed on packager.
       (packages request
         :yum [\"git\" \"git-email\"]
         :aptitude [\"git-core\" \"git-email\"])"
  [request & {:keys [yum aptitude pacman brew] :as options}]
  (->
   request
   (for->
    [package-name (options (:target-packager request))]
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
   (:release options (stevedore/script (hostinfo/os-version-name)))
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
  [request name & {:as options}]
  (let [packager (request-map/packager request)]
    (stevedore/checked-commands
     "Package source"
     (let [key-url (-> options :aptitude :url)]
       (if (and key-url (.startsWith key-url "ppa:"))
         (stevedore/chain-commands
          (stevedore/script (install-package "python-software-properties"))
          (stevedore/script (add-apt-repository ~key-url)))
         (remote-file/remote-file*
          request
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
        (remote-file/remote-file*
         request
         "aptkey.tmp"
         :url (-> options :aptitude :key-url))
        (stevedore/script ("apt-key" add aptkey.tmp))))
     (when-let [key (and (= packager :yum) (-> options :yum :gpgkey))]
       (stevedore/script ("rpm" "--import" ~key))))))

(declare package-manager)

(defaggregate ^{:always-before #{`package-manager `package}} package-source
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
  {:copy-arglist pallet.resource.package/package-source*}
  (package-source-aggregate
   [request args]
   (stevedore/do-script*
    (map (fn [x] (apply package-source* request x)) args))))

(defn add-scope*
  "Add a scope to all the existing package sources. Aptitude specific."
  [type scope file]
  (stevedore/chained-script
   (var tmpfile @(mktemp -t addscopeXXXX))
   (file/cp ~file @tmpfile :preserve true)
   ("awk" "'{if ($1 ~" ~(str "/^" type "/") "&& !" ~(str "/" scope "/")
        " ) print $0 \" \" \"" ~scope  "\" ; else print; }'"
        ~file > @tmpfile)
   (file/mv @tmpfile ~file :force ~true)))

(defn add-scope
  "Add a scope to an apt source"
  [opts]
  (add-scope*
   (or (opts :type) "deb.*")
   (:scope opts)
   (or (opts :file) "/etc/apt/sources.list")))

(defmulti configure-package-manager
  "Configure the package manager"
  (fn [request packager options] packager))

(defmulti package-manager-option
  "Provide packager specific options"
  (fn [request packager option value] [packager option]))

(defmethod package-manager-option [:aptitude :proxy]
  [request packager proxy proxy-url]
  (format "ACQUIRE::http::proxy \"%s\";" proxy-url))

(defmethod package-manager-option [:yum :proxy]
  [request packager proxy proxy-url]
  (format "proxy=%s" proxy-url))

(defmethod package-manager-option [:pacman :proxy]
  [request packager proxy proxy-url]
  (format
   (str "XferCommand = /usr/bin/wget "
        "-e \"http_proxy = %s\" -e \"ftp_proxy = %s\" "
        "--passive-ftp --no-verbose -c -O %%o %%u")
   proxy-url proxy-url))

(defmethod configure-package-manager :aptitude
  [request packager {:keys [priority prox] :or {priority 50} :as options}]
  (remote-file/remote-file*
   request
   (format "/etc/apt/apt.conf.d/%spallet" priority)
   :content (string/join
             \newline
             (map
              #(package-manager-option request packager (key %) (val %))
              (dissoc options :priority)))
   :literal true))

(defmethod configure-package-manager :yum
  [request packager {:keys [proxy] :as options}]
  (stevedore/chain-commands
   (remote-file/remote-file*
    request
    "/etc/yum.pallet.conf"
    :content (string/join
              \newline
              (map
               #(package-manager-option request packager (key %) (val %))
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
  [request packager {:keys [proxy] :as options}]
  (stevedore/chain-commands
   (remote-file/remote-file*
    request
    "/etc/pacman.pallet.conf"
    :content (string/join
              \newline
              (map
               #(package-manager-option request packager (key %) (val %))
               (dissoc options :priority)))
    :literal true)
   ;; include pacman.pallet.conf from pacman.conf
   (stevedore/script
    (if (not @("fgrep" "pacman.pallet.conf" "/etc/pacman.conf"))
      (do
        ~(file/sed*
          request
          "/etc/pacman.conf"
          "a Include = /etc/pacman.pallet.conf"
          :restriction "/\\[options\\]/"))))))

(defmethod configure-package-manager :default
  [request packager {:as options}]
  (comment "do nothing"))

(defn package-manager*
  "Package management."
  [request action & options]
  (let [packager (:target-packager request)]
    (stevedore/checked-commands
     "package-manager"
     (case action
       :update (stevedore/script (update-package-list))
       :upgrade (stevedore/script (upgrade-all-packages))
       :list-installed (stevedore/script (list-installed-packages))
       :add-scope (add-scope (apply hash-map options))
       :multiverse (add-scope (apply hash-map :scope "multiverse" options))
       :universe (add-scope (apply hash-map :scope "universe" options))
       :debconf (if (= :aptitude packager)
                  (stevedore/script (apply debconf-set-selections ~options)))
       :configure (configure-package-manager request packager options)
       (throw (IllegalArgumentException.
               (str action
                    " is not a valid action for package-manager resource")))))))

(defaggregate ^{:always-before `package} package-manager
  "Package manager controls.

   `action` is one of the following:
   - :update          - update the list of available packages
   - :list-installed  - output a list of the installed packages
   - :add-scope       - enable a scope (eg. multiverse, non-free)

   To refresh the list of packages known to the pakage manager:
       (package-manager request :update)

   To enable multiverse on ubuntu:
       (package-manager request :add-scope :scope :multiverse)

   To enable non-free on debian:
       (package-manager request :add-scope :scope :non-free)"
  {:copy-arglist pallet.resource.package/package-manager*}
  (apply-package-manager
   [request package-manager-args]
   (stevedore/do-script*
    (map #(apply package-manager* request %) (distinct package-manager-args)))))

(def ^{:private true} centos-55-repo
  "http://mirror.centos.org/centos/5.5/os/x86_64/repodata/repomd.xml")
(def ^{:private true} centos-55-repo-key
  "http://mirror.centos.org/centos/RPM-GPG-KEY-CentOS-5")

(defn add-centos55-to-amzn-linux
  "Add the centos 5.5 repository to Amazon Linux. Ensure that it has a lower
   than default priority."
  [request]
  (-> request
      (package "yum-priorities")
      (package-source
       "Centos-5.5"
       :yum {:url centos-55-repo
             :gpgkey centos-55-repo-key
             :priority 50})))

(defn add-debian-backports
  "Add debian backport source"
  [request]
  (package-source
   request
   "debian-backports"
   :aptitude {:url "http://backports.debian.org/debian-backports"
              :release (str
                        (stevedore/script (hostinfo/os-version-name))
                        "-backports")
              :scopes ["main"]}))

;; this is an aggregate so that it can come before the aggragate package-manager
(defaggregate ^{:always-before #{`package-manager `package}} add-epel
  "Add the EPEL repository"
  {:use-arglist [request & {:keys [version] :or {version "5-4"}}]}
  (add-epel*
   [request args]
   (let [{:keys [version] :or {version "5-4"}} (apply
                                                merge {}
                                                (map #(apply hash-map %) args))]
     (stevedore/script
      ;; "Add EPEL package repository"
      ("rpm"
       -U --quiet
       ~(format
         "http://download.fedora.redhat.com/pub/epel/5/%s/epel-release-%s.noarch.rpm"
         "$(uname -i)"
         version))))))

(def ^{:private true}
  rpmforge-url-pattern
  "http://packages.sw.be/rpmforge-release/rpmforge-release-%s.%s.rf.%s.rpm")


;; this is an aggregate so that it can come before the aggragate package-manager
(defaggregate ^{:always-before #{`package-manager `package}} add-rpmforge
  "Add the rpmforge repository"
  {:use-arglist [request & {:keys [version distro arch]
                            :or {version "0.5.2-2" distro "el5" arch "i386"}}]}
  (add-rpmforge*
   [request args]
   (let [{:keys [version distro arch]
          :or {version "0.5.2-2"
               distro "el5"
               arch "i386"}} (apply hash-map (first args))]
     (stevedore/checked-script
      "Add rpmforge repositories"
      (chain-or
       (if (= "0" @(pipe (rpm -qa) (grep rpmforge) (wc -l)))
         (do
           ~(remote-file/remote-file*
             request
             "rpmforge.rpm"
             :url (format rpmforge-url-pattern version distro arch))
           ("rpm" -U --quiet "rpmforge.rpm"))))))))

(def jpackage-mirror-fmt
  "http://www.jpackage.org/mirrorlist.php?dist=%s&type=free&release=%s")

(defn add-jpackage
  "Add the jpackage repository.  component should be one of:
     fedora
     redhat-el

   Installs the jpackage-utils package from the base repos at a
   pritority of 25."
  [request & {:keys [version component releasever]
              :or {component "redhat-el"
                   releasever "$releasever"
                   version "5.0"}}]
  (->
   request
   (package-source
    "jpackage-generic"
    :yum {:mirrorlist (format jpackage-mirror-fmt "generic" version)
          :failovermethod "priority"
          ;;gpgkey "http://www.jpackage.org/jpackage.asc"
          :enabled 1})
   (package-source
    (format "jpackage-%s" component)
    :yum {:mirrorlist (format
                       jpackage-mirror-fmt
                       (str component "-" releasever) version)
          :failovermethod "priority"
          ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
          :enabled 1})
   (package-source
    "jpackage-generic-updates"
    :yum {:mirrorlist (format
                       jpackage-mirror-fmt "generic" (str version "-updates"))
          :failovermethod "priority"
          ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
          :enabled 1})
   (package-source
    (format "jpackage-%s-updates" component)
    :yum {:mirrorlist (format
                       jpackage-mirror-fmt
                       (str component "-" releasever) (str version "-updates"))
          :failovermethod "priority"
          ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
          :enabled 1})
   (package
    "jpackage-utils"
    :priority 25
    :disable ["jpackage-generic"
              "jpackage-generic-updates"
              (format "jpackage-%s" component)
              (format "jpackage-%s-updates" component)])))

(defaggregate
  ^{:always-before `package-manager `package-source `package}
  minimal-packages
  "Add minimal packages for pallet to function"
  {:use-arglist [request]}
  (minimal-packages*
   [request args]
   (let [os-family (request-map/os-family request)]
     (cond
      (= :debian os-family) (stevedore/checked-script
                             "Add minimal packages"
                             (update-package-list)
                             (install-package "coreutils")
                             (install-package "sudo"))
      (= :arch os-family) (stevedore/checked-script
                           "Add minimal packages"
                           (update-package-list)
                           (install-package "sudo"))))))
