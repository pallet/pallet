(ns pallet.crate-install
  "Install methods for crates"
  (:require
   [clj-schema.schema
    :refer [def-map-schema map-schema optional-path sequence-of]]
   [clojure.tools.logging :refer [debugf tracef]]
   [pallet.action-options :refer [with-action-options]]
   [pallet.actions :as actions]
   [pallet.actions
    :refer [add-rpm
            debconf-set-selections
            package
            package-manager
            package-source-changed-flag
            remote-directory
            remote-file-arguments]]
   [pallet.contracts :refer [check-keys]]
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

;; install based on the setting's :packages key
(defmethod-plan install :packages
  [session facility instance-id]
  (let [{:keys [packages package-options preseeds] :as settings}
        (get-settings session facility {:instance-id instance-id})]
    (check-keys
     settings [:packages]
     (map-schema :strict [[:packages] (sequence-of String)])
     "packages install-strategy settings values")
    (doseq [p preseeds]
      (debconf-set-selections session p))
    (doseq [p packages]
      (apply-map package session p package-options))))

;; Install based on the setting's :package-source and :packages keys.
;; This will cause a package update if the package source definition
;; changes.
(defmethod-plan install :package-source
  [session facility instance-id]
  (let [{:keys [package-source packages package-options preseeds] :as settings}
        (get-settings session facility {:instance-id instance-id})]
    (debugf "package source %s %s" facility package-source)
    (check-keys
     settings [:package-source :packages]
     (map-schema :strict
                 [[:package-source] (map-schema :loose [[:name] String])
                  [:packages] (sequence-of String)])
     "package-source install-strategy settings values")
    (apply-map actions/package-source session (:name package-source) package-source)
    (let [modified? true ;; TODO (target-flag? package-source-changed-flag)
          ]
      (with-action-options session {:always-before #{package}}
        (when modified?
          (package-manager session :update)))
      (tracef "packages %s options %s" (vec packages) package-options)
      (doseq [p preseeds]
        (debconf-set-selections session p))
      (doseq [p packages]
        (apply-map package session p package-options)))))

;; install based on a rpm
(defmethod-plan install :rpm
  [session facility instance-id]
  (let [{:keys [rpm] :as settings}
        (get-settings session facility {:instance-id instance-id})]
    (check-keys
     settings [:rpm]
     (map-schema
      :strict
      [[:rpm] (map-schema :strict remote-file-arguments [[:name] String])])
     "packages install-strategy settings values")

    (with-action-options session {:always-before `package/package}
      (apply-map add-rpm session (:name rpm) (dissoc rpm :name)))))

;; install based on a rpm that installs a package repository source
(defmethod-plan install :rpm-repo
  [session facility instance-id]
  (let [{:keys [rpm packages package-options] :as settings}
        (get-settings session facility {:instance-id instance-id})]
    (check-keys
     settings [:rpm :packages]
     (map-schema
      :strict
      [[:rpm] (map-schema :strict remote-file-arguments [[:name] String])
       [:packages] (sequence-of String)])
     "packages install-strategy settings values")
    (with-action-options session {:always-before `package/package}
      (apply-map add-rpm session (:name rpm) (dissoc rpm :name)))
    (doseq [p packages] (apply-map package session p package-options))))

;; Upload a deb archive for. Options for the :debs key are as for
;; remote-directory (e.g. a :local-file key with a path to a local tar
;; file). Pallet uploads the deb files, creates a repository from them, then
;; installs from the repository.
(defmethod-plan install :deb
  [session facility instance-id]
  (let [{:keys [debs package-source packages]}
        (get-settings session facility {:instance-id instance-id})
        path (or (-> package-source :apt :path)
                 (-> package-source :aptitude :path))]
    (with-action-options
      session
      {:action-id ::deb-install
       :always-before #{::update-package-source ::install-package-source}}
      (apply-map
       remote-directory session path
       (merge
        {:local-file-options
         {:always-before #{::update-package-source ::install-package-source}}
         :mode "755"
         :strip-components 0}
        debs))
      (repository-packages session)
      (rebuild-repository session path))
    (apply-map actions/package-source
               session (:name package-source) package-source)
    (doseq [p packages] (package session p))))

;; Install based on an archive
(defmethod-plan install :archive
  [session facility instance-id]
  (let [{:keys [install-dir install-source] :as settings}
        (get-settings session facility {:instance-id instance-id})]
    (check-keys
     settings [:install-dir :install-source]
     (map-schema :strict [[:install-dir] String [:install-source] String])
     "archive install-strategy settings values")
    (apply-map remote-directory session install-dir install-source)))
