(ns pallet.resource.package
  "Package management resource."
  (:require
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.hostinfo :as hostinfo]
   [pallet.stevedore :as stevedore]
   [pallet.request-map :as request-map]
   [pallet.script :as script]
   [pallet.utils :as utils]
   [pallet.target :as target]
   [clojure.contrib.string :as string])
  (:use
   [pallet.resource :only [defaggregate defresource]]
   [clojure.contrib.logging]
   [clojure.contrib.core :only [-?>]]))

(script/defscript update-package-list [& options])
(script/defscript install-package [name & options])
(script/defscript remove-package [name & options])
(script/defscript purge-package [name & options])

;;; Implementation to do nothing
;;; Repeating the selector makes it more explicit
(stevedore/defimpl update-package-list [#{:no-packages} #{:no-packages}]
  [& options] "")
(stevedore/defimpl install-package [#{:no-packages} #{:no-packages}]
  [package & options] "")
(stevedore/defimpl remove-package [#{:no-packages} #{:no-packages}]
  [package & options] "")
(stevedore/defimpl purge-package [#{:no-packages} #{:no-packages}]
  [package & options] "")

;;; default to aptitude
(stevedore/defimpl update-package-list [#{:aptitude}] [& options]
  (aptitude update -q ~(stevedore/option-args options)))

(stevedore/defimpl install-package [#{:aptitude}] [package & options]
  (aptitude install -q -y ~(stevedore/option-args options) ~package
            ;; show returns an error code if no package found, while install
            ;; does not.  There should be a better way than this...
            "&&" aptitude show ~package))

(stevedore/defimpl remove-package [#{:aptitude}] [package & options]
  (aptitude remove -y ~(stevedore/option-args options) ~package))

(stevedore/defimpl purge-package [#{:aptitude}] [package & options]
  (aptitude purge -y  ~(stevedore/option-args options) ~package))

;;; yum
(stevedore/defimpl update-package-list [#{:yum}] [& options]
  (yum makecache ~(stevedore/option-args options)))

(stevedore/defimpl install-package [#{:yum}] [package & options]
  (yum install -y -q ~(stevedore/option-args options) ~package))

(stevedore/defimpl remove-package [#{:yum}] [package & options]
  (yum remove ~(stevedore/option-args options) ~package))

(stevedore/defimpl purge-package [#{:yum}] [package & options]
  (yum purge ~(stevedore/option-args options) ~package))

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

(defn package*
  "Package management"
  [request package-name & {:keys [action y force purge]
                           :or {action :install y true}
                           :as options}]
  (case action
    :install (stevedore/script
              (install-package ~package-name :force ~force))
    :remove (if purge
              (stevedore/script (purge-package ~package-name))
              (stevedore/script (remove-package ~package-name)))
    :upgrade (stevedore/script (purge-package ~package-name))
    :update-package-list (stevedore/script (update-package-list))
    (throw (IllegalArgumentException.
            (str action " is not a valid action for package resource")))))

(defaggregate package
  "Package management."
  {:copy-arglist pallet.resource.package/package*}
  (package-combiner [request package-args]
   (stevedore/checked-commands*
    "Packages"
    (cons
     (stevedore/script (package-manager-non-interactive))
     (map #(apply package* request %) package-args)))))

(def source-location
  {:aptitude "/etc/apt/sources.list.d/%s.list"
   :yum "/etc/yum.repos.d/%s.repo"})

(def source-template "resource/package/source")

(defn package-source*
  "Add a packager source."
  [request name & {:as options}]
  (let [packager (request-map/packager request)]
    (stevedore/checked-commands
     "Package source"
     (let [key-url (-> options :aptitude :url)]
       (if (.startsWith key-url "ppa:")
         (stevedore/chain-commands
          (package* request "python-software-properties")
          (stevedore/script (add-apt-repository ~key-url)))
         (remote-file/remote-file*
          request
          (format (source-location packager) name)
          :template source-template
          :values (merge
                   {:source-type "deb"
                    :release (stevedore/script (os-version-name))
                    :scopes ["main"]
                    :gpgkey 0
                    :name name}
                   (options packager)))))
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
        (stevedore/script (apt-key add aptkey.tmp)))))))

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
  (let [packager (target/packager (-?> request :node-type :image))]
    (stevedore/checked-commands
     "package-manager"
     (condp = action
         :update
       (stevedore/script (update-package-list))
       :multiverse
       (let [opts (apply hash-map options)]
         (add-scope (or (opts :type) "deb.*")
                    "multiverse"
                    (or (opts :file) "/etc/apt/sources.list")))
       :universe
       (let [opts (apply hash-map options)]
         (add-scope (or (opts :type) "deb.*")
                    "universe"
                    (or (opts :file) "/etc/apt/sources.list")))
       :debconf
       (if (= :aptitude packager)
         (stevedore/script (apply debconf-set-selections ~options)))
       (throw (IllegalArgumentException.
               (str action
                    " is not a valid action for package-manager resource")))))))

(defaggregate package-manager
  "Package manager controls.
     :multiverse        - enable multiverse
     :update            - update the package manager"
  {:copy-arglist pallet.resource.package/package-manager*}
  (apply-package-manager
   [request package-manager-args]
   (stevedore/do-script*
    (map #(apply package-manager* request %) package-manager-args))))

(defresource packages
  "Install a list of packages keyed on packager"
  (packages-combiner
   [request & {:as options}]
   (package-combiner
    request
    (map vector (options (target/packager (-?> request :node-type :image)))))))
