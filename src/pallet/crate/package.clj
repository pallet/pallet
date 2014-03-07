(ns pallet.crate.package
  "A crate to control packages on a remote node."
  (:require
   [pallet.actions :as actions]
   [pallet.plan :refer [defplan plan-fn]]
   [pallet.settings :refer [get-settings update-settings]]
   [pallet.spec :as spec]
   [pallet.target :refer [packager]]
   [schema.core :as schema :refer [check required-key optional-key validate]]))

;; TODO capture order between package, package-manager, and package-source?

;; Need to allow installing certain packages before adding a package
;; source for example.

(def package-repo-schema
  {:repository String
   (optional-key :enable) schema/Bool
   (optional-key :priority) schema/Int})

(def package-schema
  [[:package] String                     ; package name
   (optional-key :allow-unsigned) String ; flag to allow unsigned pkg
   (optional-key :repository) String     ; an optional repository
   (optional-key :version) String        ; an optional exact version
   (optional-key :disable-service-start)
   schema/Any]) ; flag to disable service startup

(def package-settings-schema
  {:packages [package-schema]
   :package-repositories [package-repo-schema]})

(defn- conj-distinct
  [coll arg]
  (vec (distinct (conj (or coll []) arg))))

(def facility ::packages)

(defn package*
  "Add a package to the packages list."
  [session
   {:keys [package-name repository version] :as pkg}
   {:keys [instance-id] :as options}]
  (update-settings
   session facility options update-in [:packages] conj-distinct pkg))

(defn package
  "Add a package to the packages list."
  [session package-name & {:keys [repository version instance-id] :as options}]
  (package* session
   (assoc (dissoc options :instance-id) :package package-name)
   (select-keys options [:instance-id])))

(defn package-repository*
  "Add a package repository to the packages list."
  [session
   {:keys [repository enable] :as repo}
   {:keys [instance-id] :as options}]
  (update-settings
   session
   facility options update-in [:package-repositories] conj-distinct repo))

(defn package-repository
  "Add a package repository to the packages list."
  [session repository {:keys [instance-id] :as options}]
  (package-repository*
   session
   (assoc (dissoc options :instance-id) :repository repository)
   (select-keys options [:instance-id])))

(defplan settings
  "Initialise package repositories and packages to be installed."
  [session {:keys [packages package-repositories instance-id] :as options}]
  (doseq [repo package-repositories]
    (package-repository* session repo (select-keys options [:instance-id])))
  (doseq [pkg packages]
    (package* session pkg (select-keys options [:instance-id]))))

(defn install
  "Install package repositories and packages."
  [session {:keys [instance-id]}]
  (let [{:keys [packages package-repositories]}
        (get-settings session facility {:instance-id instance-id})]
    (doseq [repo package-repositories]
      (actions/package-source
       session (:repository repo) (dissoc repo :repository)))
    ;; TODO automatic package manager update of changed repo defintions
    (doseq [[options packages] (group-by #(dissoc % :package) packages)]
      (actions/packages session (map :package packages) options))))

(defn server-spec
  "Return a server spec that will install packages, as specified
by calls to `package` and `package-repository`"
  [{:keys [packages package-repositories instance-id] :as options}]
  (spec/server-spec
   :phases {:settings (plan-fn [session]
                       (settings session options))
            :install (plan-fn [session]
                      (install session (select-keys options [:instance-id])))}))
