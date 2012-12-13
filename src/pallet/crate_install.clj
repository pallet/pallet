(ns pallet.crate-install
  "Install methods for crates"
  (:use
   [clojure.tools.logging :only [debugf tracef]]
   [pallet.action :only [with-action-options]]
   [pallet.actions
    :only [add-rpm package package-source package-manager
           package-source-changed-flag pipeline-when remote-directory]]
   [pallet.crate :only [get-settings defmulti-plan defmethod-plan target-flag?]]
   [pallet.crate.package-repo :only [repository-packages rebuild-repository]]
   [pallet.monad :only [chain-s let-s]]
   [pallet.monad.state-monad :only [m-map]]
   [pallet.utils :only [apply-map]])
  (:require
   [pallet.actions :as actions]))

;;; ## Install helpers
(defmulti-plan install
  (fn [facility instance-id]
    (let-s
      [settings (get-settings facility {:instance-id instance-id})]
      (:install-strategy settings))))

;; install based on the setting's :packages key
(defmethod-plan install :packages
  [facility instance-id]
  [{:keys [packages]} (get-settings facility {:instance-id instance-id})]
  (map package packages))

;; Install based on the setting's :package-source and :packages keys.
;; This will cause a package update if the package source definition
;; changes.
(defmethod-plan install :package-source
  [facility instance-id]
  [{:keys [package-source packages package-options]}
   (get-settings facility {:instance-id instance-id})]
  (m-result (debugf "package source %s %s" facility package-source))
  (apply-map actions/package-source (:name package-source) package-source)
  [modified? (m-result (target-flag? package-source-changed-flag))]
  (pipeline-when modified?
   (package-manager :update))
  (m-result (tracef "packages %s options %s" (vec packages) package-options))
  (m-map #(apply-map package % package-options) packages))

;; install based on a rpm
(defmethod-plan install :rpm
  [facility instance-id]
  [{:keys [rpm]} (get-settings facility {:instance-id instance-id})]
  (with-action-options {:always-before `package/package}
    (add-rpm (:name rpm) rpm)))

;; install based on a rpm that installs a package repository source
(defmethod-plan install :rpm-repo
  [facility instance-id]
  [{:keys [rpm packages]} (get-settings facility {:instance-id instance-id})]
  (with-action-options {:always-before `package/package}
    (add-rpm (:name rpm) rpm))
  (map package packages))

;; Upload a deb archive for. Options for the :debs key are as for
;; remote-directory (e.g. a :local-file key with a path to a local tar
;; file). Pallet uploads the deb files, creates a repository from them, then
;; installs from the repository.
(defmethod-plan install :deb
  [facility instance-id]
  [{:keys [debs package-source packages]} (get-settings
                                           facility {:instance-id instance-id})
   path (m-result (-> package-source :aptitude :path))]
  (with-action-options
      {:action-id ::deb-install
       :always-before #{::update-package-source ::install-package-source}}
    (chain-s
     (apply-map
      remote-directory path
      (merge
       {:local-file-options
        {:always-before #{::update-package-source ::install-package-source}}
        :mode "755"
        :strip-components 0}
       debs))
     (repository-packages)
     (rebuild-repository path)))
  (apply-map actions/package-source (:name package-source) package-source)
  (map package packages))
