(ns pallet.crate-install
  "Install methods for crates"
  (:use
   [pallet.action :only [with-action-options]]
   [pallet.action.package :only [add-rpm package-source package]]
   [pallet.action.remote-directory :only [remote-directory]]
   [pallet.crate.package-repo :only [repository-packages rebuild-repository]]
   [pallet.parameter :only [get-target-settings]]
   [pallet.thread-expr :only [apply-map-> for->]]
   [pallet.utils :only [apply-map]])
  (:require
   [pallet.action.package :as package]))

;;; ## Install helpers
(defmulti install
  (fn [session facility instance-id]
    (let [settings (get-target-settings session facility instance-id)]
      (:install-strategy settings))))

;; install based on the setting's :packages key
(defmethod install :packages
  [session facility instance-id]
  (let [{:keys [packages]} (get-target-settings session facility instance-id)]
    (-> session
        (for-> [pkg packages] (package pkg)))))

;; install based on the setting's :package-source and :packages keys
(defmethod install :package-source
  [session facility instance-id]
  (let [{:keys [package-source packages]}
        (get-target-settings session facility instance-id)]
    (-> session
        (package/package-source (:name package-source) package-source)
        (for-> [pkg packages] (package pkg)))))

;; install based on a rpm
(defmethod install :rpm
  [session facility instance-id]
  (let [{:keys [rpm]} (get-target-settings session facility instance-id)]
    (-> session
        (with-action-options {:always-before `package/package}
          (add-rpm (:name rpm) rpm)))))

;; install based on a rpm that installs a package repository source
(defmethod install :rpm-repo
  [session facility instance-id]
  (let [{:keys [rpm packages]}
        (get-target-settings session facility instance-id)]
    (-> session
        (with-action-options {:always-before `package/package}
          (add-rpm (:name rpm) rpm))
        (for-> [pkg packages] (package pkg)))))

;; Upload a deb archive for. Options for the :debs key are as for
;; remote-directory (e.g. a :local-file key with a path to a local tar
;; file). Pallet uploads the deb files, creates a repository from them, then
;; installs from the repository.
(defmethod install :deb
  [session facility instance-id]
  (let [{:keys [debs package-source packages]}
        (get-target-settings session facility instance-id)
        path (-> package-source :aptitude :path)]
    (-> session
        (with-action-options
          {:action-id ::deb-install
           :always-before #{::update-package-source ::install-package-source}}
          (apply-map->
           remote-directory path
           (merge
            {:local-file-options
             {:always-before #{::update-package-source
                               ::install-package-source}}
             :mode "755"
             :strip-components 0}
            debs))
          (repository-packages)
          (rebuild-repository path))
        (package-source (:name package-source) package-source)
        (for-> [pkg packages] (package pkg)))))

(defn install-strategy
  "Returns the settings with :install-strategy inferred from keys present in the
settings. Recognises :package-source, :packages, :rpm and :debs keys."
  [settings]
  (cond
   (:install-strategy settings) settings

   (and (:package-source settings) (:packages settings))
   (assoc settings :install-strategy :package-source)

   (and (:rpm settings) (:packages settings))
   (assoc settings :install-strategy :rpm-repo)

   (:packages settings) (assoc settings :install-strategy :packages)
   (:rpm settings) (assoc settings :install-strategy :rpm)
   (:debs settings) (assoc settings :install-strategy :deb)
   :else settings))
