(ns pallet.crate-install
  "Install methods for crates"
  (:require
   [clj-schema.schema
    :refer [def-map-schema map-schema optional-path sequence-of]]
   [clojure.tools.logging :refer [debugf tracef]]
   [pallet.action :refer [with-action-options]]
   [pallet.actions :as actions]
   [pallet.actions
    :refer [add-rpm
            debconf-set-selections
            package
            package-manager
            package-source-changed-flag
            plan-when
            remote-directory
            remote-file-arguments]]
   [pallet.contracts :refer [check-keys]]
   [pallet.crate
    :refer [defmethod-plan defmulti-plan get-settings target-flag?]]
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
(defmulti-plan install
  (fn [facility instance-id]
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

;; install based on the setting's :packages key
(defmethod-plan install :packages
  [facility instance-id]
  (let [{:keys [packages package-options preseeds] :as settings}
        (get-settings facility {:instance-id instance-id})]
    (check-keys
     settings [:packages]
     (map-schema :strict [[:packages] (sequence-of String)])
     "packages install-strategy settings values")
    (doseq [p preseeds]
      (debconf-set-selections p))
    (doseq [p packages]
      (apply-map package p package-options))))

;; Install based on the setting's :package-source and :packages keys.
;; This will cause a package update if the package source definition
;; changes.
(defmethod-plan install :package-source
  [facility instance-id]
  (let [{:keys [package-source packages package-options preseeds] :as settings}
        (get-settings facility {:instance-id instance-id})]
    (debugf "package source %s %s" facility package-source)
    (check-keys
     settings [:package-source :packages]
     (map-schema :strict
                 [[:package-source] (map-schema :loose [[:name] String])
                  [:packages] (sequence-of String)])
     "package-source install-strategy settings values")
    (apply-map actions/package-source (:name package-source) package-source)
    (let [modified? (target-flag? package-source-changed-flag)]
      (with-action-options {:always-before #{package}}
        (plan-when modified?
          (package-manager :update)))
      (tracef "packages %s options %s" (vec packages) package-options)
      (doseq [p preseeds]
        (debconf-set-selections p))
      (doseq [p packages]
        (apply-map package p package-options)))))

;; install based on a rpm
(defmethod-plan install :rpm
  [facility instance-id]
  (let [{:keys [rpm] :as settings}
        (get-settings facility {:instance-id instance-id})]
    (check-keys
     settings [:rpm]
     (map-schema
      :strict
      [[:rpm] (map-schema :strict remote-file-arguments [[:name] String])])
     "packages install-strategy settings values")

    (with-action-options {:always-before `package/package}
      (apply-map add-rpm (:name rpm) (dissoc rpm :name)))))

;; install based on a rpm that installs a package repository source
(defmethod-plan install :rpm-repo
  [facility instance-id]
  (let [{:keys [rpm packages package-options] :as settings}
        (get-settings facility {:instance-id instance-id})]
    (check-keys
     settings [:rpm :packages]
     (map-schema
      :strict
      [[:rpm] (map-schema :strict remote-file-arguments [[:name] String])
       [:packages] (sequence-of String)])
     "packages install-strategy settings values")
    (with-action-options {:always-before `package/package}
      (apply-map add-rpm (:name rpm) (dissoc rpm :name)))
    (doseq [p packages] (apply-map package p package-options))))

;; Upload a deb archive for. Options for the :debs key are as for
;; remote-directory (e.g. a :local-file key with a path to a local tar
;; file). Pallet uploads the deb files, creates a repository from them, then
;; installs from the repository.
(defmethod-plan install :deb
  [facility instance-id]
  (let [{:keys [debs package-source packages]}
        (get-settings facility {:instance-id instance-id})
        path (or (-> package-source :apt :path)
                 (-> package-source :aptitude :path))]
    (with-action-options
      {:action-id ::deb-install
       :always-before #{::update-package-source ::install-package-source}}
      (apply-map
       remote-directory path
       (merge
        {:local-file-options
         {:always-before #{::update-package-source ::install-package-source}}
         :mode "755"
         :strip-components 0}
        debs))
      (repository-packages)
      (rebuild-repository path))
    (apply-map actions/package-source (:name package-source) package-source)
    (doseq [p packages] (package p))))

;; Install based on an archive
(defmethod-plan install :archive
  [facility instance-id]
  (let [{:keys [install-dir install-source] :as settings}
        (get-settings facility {:instance-id instance-id})]
    (check-keys
     settings [:install-dir :install-source]
     (map-schema :strict [[:install-dir] String [:install-source] String])
     "archive install-strategy settings values")
    (apply-map remote-directory install-dir install-source)))
