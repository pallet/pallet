(ns pallet.resource.package
  "Package management resource."
  (:require
   pallet.compat
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.hostinfo :as hostinfo]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils])
  (:use pallet.script
        pallet.stevedore
        [pallet.resource :only [defaggregate defresource]]
        [clojure.contrib.logging]
        [pallet.target :only [packager]]))

(pallet.compat/require-contrib)

(defscript update-package-list [& options])
(defscript install-package [name & options])
(defscript remove-package [name & options])
(defscript purge-package [name & options])

(defimpl update-package-list :default [& options]
  (aptitude update ~(option-args options)))

(defimpl install-package :default [package & options]
  (aptitude install -y ~(option-args options) ~package))

(defimpl remove-package :default [package & options]
  (aptitude remove -y ~(option-args options) ~package))

(defimpl purge-package :default [package & options]
  (aptitude purge -y  ~(option-args options) ~package))

(defimpl update-package-list [#{:centos :rhel}] [& options]
  (yum makecache ~(option-args options)))

(defimpl install-package [#{:centos :rhel}] [package & options]
  (yum install -y ~(option-args options) ~package))

(defimpl remove-package [#{:centos :rhel}] [package & options]
  (yum remove ~(option-args options) ~package))

(defimpl purge-package [#{:centos :rhel}] [package & options]
  (yum purge ~(option-args options) ~package))


(defscript debconf-set-selections [& selections])
(defimpl debconf-set-selections :default [& selections]
  ("{ debconf-set-selections"
   ~(str "<<EOF\n" (string/join \newline selections) "\nEOF\n}")))

(defscript package-manager-non-interactive [])
(defimpl package-manager-non-interactive :default []
  (debconf-set-selections
   "debconf debconf/frontend select noninteractive"
   "debconf debconf/frontend seen false"))

(def source-location
     {:aptitude "/etc/apt/sources.list.d/%s.list"
      :yum "/etc/yum.repos.d/%s.repo"})

(def source-template "resource/package/source")

(defn package-source*
  "Add a packager source."
  [name & options]
  (let [options (apply hash-map options)]
    (stevedore/do-script
     (remote-file/remote-file*
      (format (source-location (packager)) name)
      :template source-template
      :values (merge
               {:source-type "deb"
                :release (stevedore/script (hostinfo/os-version-name))
                :scopes ["main"]
                :gpgkey 0
                :name name}
               (options (packager))))
     (if (and (-> options :aptitude :key-id)
              (= (packager) :aptitude))
        (stevedore/script
         (apt-key adv "--recv-keys" ~(option-args options))))
     (if (and (-> options :aptitude :key-url)
              (= (packager) :aptitude))
       (stevedore/do-script
        (remote-file/remote-file*
         "aptkey.tmp"
         :url (-> options :aptitude :key-url))
        (stevedore/script (apt-key add aptkey.tmp)))
       ""))))

(defaggregate package-source
  "Control package sources.
   Options are the package manager keywords, each specifying a map of
   packager specific options.

   :aptitude
     :source-type string   - source type (deb)
     :url url              - repository url
     :scope seq            - scopes to enable for repository
     :key-url url          - url for key
     :key-id id            - id for key to look it up from keyserver

   :yum
     :url url              - repository url
     :gpgkey keystring     - pgp key string for repository"
  #(stevedore/do-script* (map (fn [x] (apply package-source* x)) %))
  [name packager-map & options])

(defn package*
  "Package management"
  [package-name & options]
  (let [opts (if options (apply assoc {} options) {})
        opts (merge {:action :install} opts)
        action (opts :action)]
    (condp = action
      :install
      (script
       (apply install-package
              ~package-name
              ~(apply concat (select-keys opts [:y :force]))))
      :remove
      (if (opts :purge)
        (script (purge-package ~package-name))
        (script (remove-package ~package-name)))
      :upgrade
      (script (purge-package ~package-name))
      :update-package-list
      (script (update-package-list))
      (throw (IllegalArgumentException.
              (str action " is not a valid action for package resource"))))))

(defn- apply-packages [package-args]
  (stevedore/do-script*
   (cons
    (script (package-manager-non-interactive))
    (map #(apply package* %) package-args))))

(defaggregate package "Package management."
  apply-packages [packagename & options])

(defn add-scope
  "Add a scope to all the existing package sources"
  [type scope file]
  (script
   (var tmpfile @(mktemp addscopeXXXX))
   (cp "-p" ~file @tmpfile)
   (awk "'{if ($1 ~" ~(str "/^" type "/") "&& !" ~(str "/" scope "/")
        " ) print $0 \" \" \"" ~scope  "\" ; else print; }' "
        ~file " > " @tmpfile " && mv -f" @tmpfile ~file )))

(defn package-manager*
  "Package management."
  [action & options]
  (let [opts (apply hash-map options)]
    (stevedore/checked-commands "package-manager"
     (condp = action
       :update
       (script (update-package-list))
       :multiverse
       (add-scope (or (opts :type) "deb.*")
                  "multiverse"
                  (or (opts :file) "/etc/apt/sources.list"))
       :universe
       (add-scope (or (opts :type) "deb.*")
                  "universe"
                  (or (opts :file) "/etc/apt/sources.list"))
       :debconf
       (if (= :aptitude (packager))
         (script (apply debconf-set-selections ~options)))
       (throw (IllegalArgumentException.
               (str action " is not a valid action for package resource")))))))

(defn- apply-package-manager [package-manager-args]
  (stevedore/do-script*
   (map #(apply package-manager* %) package-manager-args)))

(defaggregate package-manager
  "Package manager controls.
:multiverse        - enable multiverse
:update            - update the package manager"
  apply-package-manager [action & options])

(defn packages*
  [& options]
  (let [opts (apply array-map options)]
    (apply stevedore/checked-commands
           "Packages"
           (map package* (opts (packager))))))

(defresource packages
  "Install a list of packages keyed on packager"
  packages* [& options])
