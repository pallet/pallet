(ns pallet.crate-install
  "Install methods for crates"
  (:require
   [clj-schema.schema
    :refer [def-map-schema map-schema optional-path sequence-of]]
   [clojure.tools.logging :refer [debugf tracef]]
   [pallet.action :refer [with-action-options]]
   [pallet.actions :as actions]
   [pallet.actions
    :as actions
    :refer [add-rpm
            debconf-set-selections
            package
            package-manager
            package-source-changed-flag
            plan-when
            remote-directory
            remote-directory-arguments
            remote-file-arguments]]
   [pallet.contracts :refer [check-keys]]
   [pallet.crate
    :refer [defmethod-plan defmulti-plan get-settings target-flag?]]
   [pallet.crate.package.epel :refer [add-epel]]
   [pallet.crate.package.debian-backports :refer [add-debian-backports]]
   [pallet.crate.package-repo :refer [rebuild-repository repository-packages]]
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
  "Install based on a settings map.  The :install-strategy key determines
  the install strategy used.  Each strategy has it's own set of keywords
  used to configure the strategy."
  (fn [;; {:keys [install-strategy] :as settings}
       settings]
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
  "Install facility using the settings in the given instance-id."
  (fn [facility instance-id]
    ;; {:pre [(keyword? facility)]}
    (let [settings (get-settings facility {:instance-id instance-id})]
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
  [facility instance-id]
  {:pre [(keyword? facility)]}
  (let [settings (get-settings facility {:instance-id instance-id})]
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
    (clojure.tools.logging/errorf "install %s" settings)
    (install-from settings)))

;; install based on the setting's :packages key
(defmethod-plan install-from :packages
  [{:keys [packages package-options preseeds] :as settings}]
  (check-keys
   settings [:packages]
   (map-schema :strict [[:packages] (sequence-of String)])
   "packages install-strategy settings values")
  (doseq [p preseeds]
    (debconf-set-selections p))
  (doseq [p packages]
    (apply-map package p package-options)))

;; Install based on the setting's :package-source and :packages keys.
;; This will cause a package update if the package source definition
;; changes.
(defmethod-plan install-from :package-source
  [{:keys [package-source packages package-options preseeds repository]
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
    (apply-map actions/package-source (:name package-source) package-source))
  (let [modified? (target-flag? package-source-changed-flag)]
    (with-action-options {:always-before #{package}}
      (plan-when modified?
        (package-manager :update)))
    (tracef "packages %s options %s" (vec packages) package-options)
    (doseq [p preseeds]
      (debconf-set-selections p))
    (doseq [p packages]
      (apply-map package p package-options))))

;; install based on a rpm
(defmethod-plan install-from :rpm
  [{:keys [rpm] :as settings}]
  (check-keys
   settings [:rpm]
   (map-schema
    :strict
    [[:rpm] (map-schema :strict remote-file-arguments [[:name] String])])
   "packages install-strategy settings values")

  (with-action-options {:always-before `package/package}
    (apply-map add-rpm (:name rpm) (dissoc rpm :name))))

;; install based on a rpm that installs a package repository source
(defmethod-plan install-from :rpm-repo
  [{:keys [rpm packages package-options] :as settings}]
  (check-keys
   settings [:rpm :packages]
   (map-schema
    :strict
    [[:rpm] (map-schema :strict remote-file-arguments [[:name] String])
     [:packages] (sequence-of String)])
   "packages install-strategy settings values")
  (with-action-options {:always-before `package/package}
    (apply-map add-rpm (:name rpm) (dissoc rpm :name)))
  (doseq [p packages] (apply-map package p package-options)))

;; Upload a deb archive for. Options for the :debs key are as for
;; remote-directory (e.g. a :local-file key with a path to a local tar
;; file). Pallet uploads the deb files, creates a repository from them, then
;; installs from the repository.
(defmethod-plan install-from :deb
  [{:keys [debs package-source packages]}]
  (let [path (or (-> package-source :apt :path)
                 (-> package-source :aptitude :path))]
    (with-action-options
      {:action-id ::deb-install
       :always-before #{::update-package-source ::install-package-source}}
      (apply-map
       remote-directory path
       (merge
        {:mode "755"
         :strip-components 0}
        (dissoc debs :name)))
      (repository-packages)
      (rebuild-repository path))
    (apply-map actions/package-source (:name package-source) package-source)
    (doseq [p packages] (package p))))

;; Install based on an archive
(defmethod-plan install-from :archive
  [{:keys [install-dir install-source] :as settings}]
  (check-keys
   settings [:install-dir :install-source]
   (map-schema :strict
               [[:install-dir] String
                [:install-source] remote-directory-arguments])
   "archive install-strategy settings values")
  (apply-map remote-directory install-dir install-source))
