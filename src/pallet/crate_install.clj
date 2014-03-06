(ns pallet.crate-install
  "Install methods for crates"
  (:require
   [clj-schema.schema
    :refer [def-map-schema map-schema optional-path sequence-of]]
   [clojure.tools.logging :refer [debugf tracef]]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions :as actions]
   [pallet.actions
    :as actions
    :refer [add-rpm
            debconf-set-selections
            package
            package-manager
            package-source-changed-flag
            remote-directory
            remote-directory-arguments
            remote-file-arguments]]
   [pallet.contracts :refer [check-keys]]
   [pallet.crate.package.epel :refer [add-epel]]
   [pallet.crate.package.debian-backports :refer [add-debian-backports]]
   [pallet.crate.package-repo :refer [rebuild-repository repository-packages]]
   [pallet.plan :refer [defmethod-plan defmulti-plan]]
   [pallet.settings :refer [get-settings]]
   [pallet.utils :refer [apply-map]])
  (:import clojure.lang.IPersistentVector
           clojure.lang.Keyword))

(def-map-schema crate-install-settings
  :strict
  [[:install-strategy] Keyword
   (optional-path [:packages]) (sequence-of String)
   (optional-path [:package-source]) (map-schema :loose [[:name] String])
   (optional-path [:package-options]) (map-schema :loose [])
   (optional-path [:preseeds]) (sequence-of IPersistentVector)
   (optional-path [:rpm]) (map-schema
                           :strict remote-file-arguments [[:name] String])
   (optional-path [:debs]) remote-file-arguments
   (optional-path [:install-source]) remote-file-arguments
   (optional-path [:install-dir]) remote-file-arguments])


;;; ## Install helpers
(defmulti-plan install-from
  "Install based on a map.  The :install-strategy key determines
  the install strategy used.  Each strategy has it's own set of keywords
  used to configure the strategy.

## `:packages`

`:packages`
: a sequence of strings specifying the package names

`:package-options`
: a map of package options, as accepted by the `package` action.

`:preseeds`
: a map of keywords and values  used to set preseeds for the packages.


## `:package-source`

Install based on the setting's :package-source and :packages keys.
This will cause a package update if the package source definition
changes.

`:package-source`
A map of options as accepted by the `package-source` action.

`:repository`
A keyword that enables a repository with an implementation in the
`repository` action multimethod.

The packages to be installed are specified as for the `:packages`
install strategy.


## `:rpm`
Install based on a rpm

The value is a map specifying the source of the rpm, using options as
per the `remote-file` action.


## `:rpm-repo`

Install based on a rpm that installs a package repository source.

`:rpm`
: remote-file options to specify the rpm that will install the
repository source.

`:packages`
: a sequence of package names to install from the repository.

`:package-options`
: package options, as per the `:packages` install strategy.

## `:deb`

Upload a deb archive repository. Options for the :debs key are as for
remote-directory (e.g. a :local-file key with a path to a local tar
file). Pallet uploads the deb files, creates a repository from them,
then installs from the repository.

`:debs`
: remote-directory options for the source of the deb  package-source packages

`:packages`
: a sequence of package names to install from the repository.

`:package-source`
: a package source definition for the repository


## `:archive`

Install based on an archive

`:install-dir`
: a path where the archive should be installed.

`:install-source`
: remote-directory options specifying the source of the archive."
  (fn [session settings]
    (when-not (:install-strategy settings)
      (throw (ex-info
              (str "No :install-strategy found in settings: " settings)
              {:settings settings})))
    (:install-strategy settings)))

;; Would like this but need to preserve backward compatibility,
;; so make it a multimethod

;; (defn install
;;   "Install facility using the settings in the given instance-id."
;;   [facility instance-id]
;;   {:pre [(keyword? facility)]}
;;   (let [settings (get-settings facility {:instance-id instance-id})]
;;     (when-not settings
;;       (throw (ex-info
;;               (str "No settings found for facility " facility)
;;               {:facility facility
;;                :instance-id instance-id})))
;;     (when-not (:install-strategy settings)
;;       (throw (ex-info
;;               (str "No :install-strategy found in settings for facility "
;;                    facility)
;;               {:facility facility
;;                :instance-id instance-id})))
;;     (install-from settings)))

(defmulti-plan install
  (fn [session facility instance-id]
    (let [settings (get-settings session facility {:instance-id instance-id})]
      (when-not settings
        (throw (ex-info
                (str "No settings found for facility " facility)
                {:facility facility
                 :instance-id instance-id})))
      (when-not (:install-strategy settings)
        (throw (ex-info
                (str "No :install-strategy found in settings for facility "
                     facility)
                {:facility facility
                 :instance-id instance-id})))
      (:install-strategy settings))))

(defmethod-plan install :default
  [session facility instance-id]
  {:pre [(keyword? facility)]}
  (let [settings (get-settings session facility {:instance-id instance-id})]
    (when-not settings
      (throw (ex-info
              (str "No settings found for facility " facility)
              {:facility facility
               :instance-id instance-id})))
    (when-not (:install-strategy settings)
      (throw (ex-info
              (str "No :install-strategy found in settings for facility "
                   facility)
              {:facility facility
               :instance-id instance-id})))
    (tracef "install %s" settings)
    (install-from session settings)))

;; install based on the setting's :packages key
(defmethod-plan install-from :packages
  [session {:keys [packages package-options preseeds] :as settings}]
  (check-keys
   settings [:packages]
   (map-schema :strict [[:packages] (sequence-of String)])
   "packages install-strategy settings values")
  (doseq [p preseeds]
    (debconf-set-selections session p))
  (doseq [p packages]
    (package session p package-options)))

;; Install based on the setting's :package-source and :packages keys.
;; This will cause a package update if the package source definition
;; changes.
(defmethod-plan install-from :package-source
  [session {:keys [package-source packages package-options preseeds repository]
            :as settings}]
  (debugf "package source %s %s" package-source repository)
  (check-keys
   settings [:package-source :packages]
   (map-schema :strict
     [(optional-path [:package-source]) (map-schema
                                            :loose [[:name] String])
      (optional-path [:repository]) (map-schema
                                        :loose [[:repository] Keyword])
      [:packages] (sequence-of String)])
   "package-source install-strategy settings values")
  (if repository
    (actions/repository repository)
    (actions/package-source session (:name package-source) package-source))
  (let [modified? true ;; TODO (target-flag? package-source-changed-flag)
        ]
    (with-action-options session {:always-before #{package}}
      (when modified?
        (package-manager session :update)))
    (tracef "packages %s options %s" (vec packages) package-options)
    (doseq [p preseeds]
      (debconf-set-selections session p))
    (doseq [p packages]
      (package session p package-options))))

;; install based on a rpm
(defmethod-plan install-from :rpm
  [session {:keys [rpm] :as settings}]
  (check-keys
   settings [:rpm]
   (map-schema
       :strict
     [[:rpm] (map-schema :strict remote-file-arguments [[:name] String])])
   "packages install-strategy settings values")

  (with-action-options session {:always-before `package/package}
    (add-rpm session (:name rpm) (dissoc rpm :name))))

;; install based on a rpm that installs a package repository source
(defmethod-plan install-from :rpm-repo
  [session {:keys [rpm packages package-options] :as settings}]
  (check-keys
   settings [:rpm :packages]
   (map-schema
       :strict
     [[:rpm] (map-schema :strict remote-file-arguments [[:name] String])
      [:packages] (sequence-of String)])
   "packages install-strategy settings values")
  (with-action-options session {:always-before `package/package}
    (add-rpm session (:name rpm) (dissoc rpm :name)))
  (doseq [p packages] (apply-map package session p package-options)))

;; Upload a deb archive for. Options for the :debs key are as for
;; remote-directory (e.g. a :local-file key with a path to a local tar
;; file). Pallet uploads the deb files, creates a repository from them, then
;; installs from the repository.
(defmethod-plan install-from :deb
  [session {:keys [debs package-source packages]}]
  (let [path (or (-> package-source :apt :path)
                 (-> package-source :aptitude :path))]
    (with-action-options
      session
      {:action-id ::deb-install
       :always-before #{::update-package-source ::install-package-source}}
      (remote-directory session path
       (merge
        {:mode "755"
         :strip-components 0}
        (dissoc debs :name)))
      (repository-packages session)
      (rebuild-repository session path))
      (actions/package-source session (:name package-source) package-source)
    (doseq [p packages] (package session p))))

;; Install based on an archive
(defmethod-plan install-from :archive
  [session {:keys [install-dir install-source] :as settings}]
  (check-keys
   settings [:install-dir :install-source]
   (map-schema :strict [[:install-dir] String [:install-source] String])
   "archive install-strategy settings values")
  (apply-map remote-directory session install-dir install-source))
