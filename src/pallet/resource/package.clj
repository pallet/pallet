(ns pallet.resource.package
  "Package management resource."
  (:require
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.hostinfo :as hostinfo]
   [pallet.resource.exec-script :as exec-script]
   [pallet.stevedore :as stevedore]
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

(script/defscript update-package-list [& options])
(script/defscript install-package [name & options])
(script/defscript remove-package [name & options])
(script/defscript purge-package [name & options])
(script/defscript list-installed-packages [& options])

;;; Implementation to do nothing
;;; Repeating the selector makes it more explicit
(stevedore/defimpl update-package-list [#{:no-packages} #{:no-packages}]
  [& options] "")
(stevedore/defimpl install-package [#{:no-packages} #{:no-packages}]
  [package & options] "")
(stevedore/defimpl upgrade-package [#{:no-packages} #{:no-packages}]
  [package & options] "")
(stevedore/defimpl remove-package [#{:no-packages} #{:no-packages}]
  [package & options] "")
(stevedore/defimpl purge-package [#{:no-packages} #{:no-packages}]
  [package & options] "")
(stevedore/defimpl list-installed-packages [#{:no-packages} #{:no-packages}]
  [& options] "")

;;; aptitude
(stevedore/defimpl update-package-list [#{:aptitude}] [& options]
  (chain-or
   (aptitude update ~(stevedore/option-args options)) true))

(stevedore/defimpl install-package [#{:aptitude}] [package & options]
  (aptitude install -q -y ~(stevedore/option-args options) ~package
            ;; show returns an error code if no package found, while install
            ;; does not.  There should be a better way than this...
            "&&" aptitude show ~package))

(stevedore/defimpl upgrade-package [#{:aptitude}] [package & options]
  (aptitude install -q -y ~(stevedore/option-args options) ~package
            ;; show returns an error code if no package found, while install
            ;; does not.  There should be a better way than this...
            "&&" aptitude show ~package))

(stevedore/defimpl remove-package [#{:aptitude}] [package & options]
  (aptitude remove -y ~(stevedore/option-args options) ~package))

(stevedore/defimpl purge-package [#{:aptitude}] [package & options]
  (aptitude purge -y  ~(stevedore/option-args options) ~package))

(stevedore/defimpl list-installed-packages [#{:aptitude}] [& options]
  (aptitude search (quoted "~i")))

;;; yum
(stevedore/defimpl update-package-list [#{:yum}] [& options]
  (yum makecache ~(stevedore/option-args options)))

(stevedore/defimpl install-package [#{:yum}] [package & options]
  (yum install -y -q ~(stevedore/option-args options) ~package))

(stevedore/defimpl upgrade-package [#{:yum}] [package & options]
  (yum upgrade -y -q ~(stevedore/option-args options) ~package))

(stevedore/defimpl remove-package [#{:yum}] [package & options]
  (yum remove ~(stevedore/option-args options) ~package))

(stevedore/defimpl purge-package [#{:yum}] [package & options]
  (yum purge ~(stevedore/option-args options) ~package))

(stevedore/defimpl list-installed-packages [#{:yum}] [& options]
  (yum list installed))

;;; zypper
(stevedore/defimpl update-package-list [#{:zypper}] [& options]
  (zypper refresh ~(stevedore/option-args options)))

(stevedore/defimpl install-package [#{:zypper}] [package & options]
  (zypper install -y ~(stevedore/option-args options) ~package))

(stevedore/defimpl remove-package [#{:zypper}] [package & options]
  (zypper remove ~(stevedore/option-args options) ~package))

(stevedore/defimpl purge-package [#{:zypper}] [package & options]
  (zypper remove ~(stevedore/option-args options) ~package))

;;; pacman
(stevedore/defimpl update-package-list [#{:pacman}] [& options]
  (pacman -Sy "--noconfirm" "--noprogressbar" ~(stevedore/option-args options)))

(stevedore/defimpl install-package [#{:pacman}] [package & options]
  (pacman -S "--noconfirm" "--noprogressbar"
          ~(stevedore/option-args options) ~package))

(stevedore/defimpl upgrade-package [#{:pacman}] [package & options]
  (pacman -S "--noconfirm" "--noprogressbar"
          ~(stevedore/option-args options) ~package))

(stevedore/defimpl remove-package [#{:pacman}] [package & options]
  (pacman -R "--noconfirm" ~(stevedore/option-args options) ~package))

(stevedore/defimpl purge-package [#{:pacman}] [package & options]
  (pacman -R "--noconfirm" ~(stevedore/option-args options) ~package))

;; brew
(stevedore/defimpl update-package-list [#{:brew}] [& options]
  (brew update ~(stevedore/option-args options)))

(stevedore/defimpl install-package [#{:brew}] [package & options]
  (brew install -y ~(stevedore/option-args options) ~package))

(stevedore/defimpl remove-package [#{:brew}] [package & options]
  (brew uninstall ~(stevedore/option-args options) ~package))

(stevedore/defimpl purge-package [#{:brew}] [package & options]
  (brew uninstall ~(stevedore/option-args options) ~package))

(script/defscript debconf-set-selections [& selections])
(stevedore/defimpl debconf-set-selections :default [& selections] "")
(stevedore/defimpl debconf-set-selections [#{:aptitude}] [& selections]
  ("{ debconf-set-selections"
   ~(str "<<EOF\n" (string/join \newline selections) "\nEOF\n}")))

(script/defscript package-manager-non-interactive [])
(stevedore/defimpl package-manager-non-interactive :default [] "")
(stevedore/defimpl package-manager-non-interactive [#{:aptitude}] []
  (debconf-set-selections
   "debconf debconf/frontend select noninteractive"
   "debconf debconf/frontend seen false"))

(defmulti adjust-packages
  (fn [request & _]
    (:target-packager request)))

(defmethod adjust-packages :aptitude
  [request action-packages]
  (stevedore/checked-commands
   "Packages"
   (stevedore/chain-commands
    (stevedore/script (package-manager-non-interactive))
    (stevedore/script
     (aptitude
      install -q -y
      ~(string/join
        " "
        (for [[action packages] action-packages
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
               action " is not a valid action for package resource")))))))))))

(defmethod adjust-packages :yum
  [request action-packages]
  (stevedore/checked-commands
   "Packages"
   (stevedore/chain-commands*
    (for [[action packages] action-packages]
      (stevedore/script
       (yum
        ~(name action) -q -y
        ~(string/join
          " "
          (for [{:keys [package force purge]} packages]
            package))))))))

(defmethod adjust-packages :default
  [request action-packages]
  (stevedore/checked-commands
   "Packages"
   (stevedore/chain-commands*
    (list*
     (stevedore/script (package-manager-non-interactive))
     (for [[action packages] action-packages
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
                  & {:keys [action y force purge]
                     :or {action :install y true}
                     :as options}]
  (assoc options :package package-name :action action :y y))

(defaggregate package
  "Package management.
    :action [:install | :remove | :upgrade]
    :version version
    :purge [true|false]  - when removing, whether to remove all config, etc
   Package management occurs in one shot, so that the package manager can
   maintain a consistent view."
  {:use-arglist  [request package-name
                  & {:keys [action y force purge]
                     :or {action :install y true}
                     :as options}]}
  (package*
   [request args]
   (adjust-packages
    request
    (group-by :action (map #(apply package-map request %) args)))))

(def source-location
  {:aptitude "/etc/apt/sources.list.d/%s.list"
   :yum "/etc/yum.repos.d/%s.repo"})

(defmulti format-source
  "Create a source definition"
  (fn [packager & _] packager))

(defmethod format-source :aptitude
  [_ name options]
  (format
   "%s %s %s %s\n"
   (:source-type options "deb")
   (:url options)
   (:release options (stevedore/script (os-version-name)))
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
        (stevedore/script (apt-key add aptkey.tmp))))
     (when-let [key (and (= packager :yum) (-> options :yum :gpgkey))]
       (stevedore/script (rpm "--import" ~key))))))

(defaggregate ^{:always-before `package} package-source
  "Control package sources.
   Options are the package manager keywords, each specifying a map of
   packager specific options.

   :aptitude
     :source-type string   - source type (deb)
     :url url              - repository url
     :scopes seq           - scopes to enable for repository
     :key-url url          - url for key
     :key-id id            - id for key to look it up from keyserver

   :yum
     :name                 - repository name
     :url url          - repository base url
     :gpgkey url           - gpg key url for repository

   Example: (package-source \"Partner\" :aptitude {:url \"http://archive.canonical.com/\"
                                                   :scopes [\"partner\"]})"
  {:copy-arglist pallet.resource.package/package-source*}
  (package-source-aggregate
   [request args]
   (stevedore/do-script*
    (map (fn [x] (apply package-source* request x)) args))))

(defn add-scope
  "Add a scope to all the existing package sources"
  [type scope file]
  (stevedore/script
   (var tmpfile @(mktemp -t addscopeXXXX))
   (cp "-p" ~file @tmpfile)
   (awk "'{if ($1 ~" ~(str "/^" type "/") "&& !" ~(str "/" scope "/")
        " ) print $0 \" \" \"" ~scope  "\" ; else print; }' "
        ~file " > " @tmpfile " && mv -f" @tmpfile ~file )))

(defn package-manager*
  "Package management."
  [request action & options]
  (let [packager (:target-packager request)]
    (stevedore/checked-commands
     "package-manager"
     (case action
       :update (stevedore/script (update-package-list))
       :list-installed (stevedore/script (list-installed-packages))
       :multiverse (let [opts (apply hash-map options)]
                     (add-scope (or (opts :type) "deb.*")
                                "multiverse"
                                (or (opts :file) "/etc/apt/sources.list")))
       :universe (let [opts (apply hash-map options)]
                   (add-scope (or (opts :type) "deb.*")
                              "universe"
                              (or (opts :file) "/etc/apt/sources.list")))
       :debconf (if (= :aptitude packager)
         (stevedore/script (apply debconf-set-selections ~options)))
       (throw (IllegalArgumentException.
               (str action
                    " is not a valid action for package-manager resource")))))))

(defaggregate ^{:always-before `package} package-manager
  "Package manager controls.
     :multiverse        - enable multiverse
     :update            - update the package manager"
  {:copy-arglist pallet.resource.package/package-manager*}
  (apply-package-manager
   [request package-manager-args]
   (stevedore/do-script*
    (map #(apply package-manager* request %) package-manager-args))))

(defn packages
  "Install a list of packages keyed on packager"
  [request & {:as options}]
  (->
   request
   (for->
    [package-name (options (:target-packager request))]
    (package package-name))))

(def ^{:private true} centos-55-repo
  "http://mirror.centos.org/centos/5.5/os/x86_64/repodata/repomd.xml")
(def ^{:private true} centos-55-repo-key
  "http://mirror.centos.org/centos/RPM-GPG-KEY-CentOS-5")

(defn add-centos55-to-amzn-linux
  "Add the centos 5.5 repository to Amazon Linux. Ensure that it has a lower
  priority"
  [request]
  (-> request
      (package "yum-priorities")
      (package-source
       "Centos-5.5"
       :yum {:url centos-55-repo
             :gpgkey centos-55-repo-key
             :priority 50})))

(defn add-epel
  "Add the EPEL repository"
  [request & {:keys [version] :or {version "5-4"}}]
  (->
   request
   (exec-script/exec-script
    (rpm
     -U --quiet
     ~(format
       "http://download.fedora.redhat.com/pub/epel/5/x86_64/epel-release-%s.noarch.rpm"
       version)))))

(defn add-rpmforge
  "Add the rpmforge repository"
  [request & {:keys [version distro arch]
              :or {version "0.5.1-1" distro "el5" arch "i386"}}]
  (->
   request
   (exec-script/exec-script
    (rpm
     -U --quiet
     ~(format
       "http://packages.sw.be/rpmforge-release/rpmforge-release-%s.%s.rf.%s.rpm"
       version
       distro
       arch)))))

(def jpackage-mirror-fmt
  "http://www.jpackage.org/mirrorlist.php?dist=%s&type=free&release=%s")

(defn add-jpackage
  "Add the jpackage repository.  component should be one of:
     fedora
     redhat-el"
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
    (format "jpackage-%s" component)
    :yum {:mirrorlist (format
                       jpackage-mirror-fmt
                       (str component "-" releasever) (str version "-updates"))
          :failovermethod "priority"
          ;;:gpgkey "http://www.jpackage.org/jpackage.asc"
          :enabled 1})))
