(ns pallet.crate.package
  "A crate to control packages on a remote node."
  (:require
   [clj-schema.schema :refer [def-map-schema optional-path sequence-of wild]]
   ;; [pallet.contracts :refer []]
   [pallet.api :as api]
   [pallet.actions :as actions]
   [pallet.crate :refer [get-settings update-settings]]
   [pallet.plan :refer [defplan]]
   [pallet.spec :refer [packager]]))

(def-map-schema package-repo-schema
  [[:repository] String
   (optional-path [:enable]) wild
   (optional-path [:priority]) number?])

(def-map-schema package-schema
  [[:package] String                        ; package name
   (optional-path [:allow-unsigned]) String ; flag to allow unsigned pkg
   (optional-path [:repository]) String     ; an optional repository
   (optional-path [:version]) String    ; an optional exact version
   (optional-path [:disable-service-start]) wild]) ; flag to disable
                                        ; service startup

(def-map-schema package-settings-schema
  [[:packages] (sequence-of package-schema)
   [:package-repositories] (sequence-of package-repo-schema)])

(defn- conj-distinct
  [coll arg]
  (vec (distinct (conj (or coll []) arg))))

(defn package*
  "Add a package to the packages list."
  [{:keys [package-name repository version] :as pkg}
   {:keys [instance-id] :as options}]
  (update-settings ::packages options update-in [:packages] conj-distinct pkg))

(defn package
  "Add a package to the packages list."
  [package-name & {:keys [repository version instance-id] :as options}]
  (package*
   (assoc (dissoc options :instance-id) :package package-name)
   (select-keys options [:instance-id])))

(defn package-repository*
  "Add a package repository to the packages list."
  [{:keys [repository enable] :as repo} {:keys [instance-id] :as options}]
  (update-settings
   ::packages options update-in [:package-repositories] conj-distinct repo))

(defn package-repository
  "Add a package repository to the packages list."
  [repository {:keys [instance-id] :as options}]
  (package-repository*
   (assoc (dissoc options :instance-id) :repository repository)
   (select-keys options [:instance-id])))

(defplan settings
  "Initialise package repositories and packages to be installed."
  [{:keys [packages package-repositories instance-id] :as options}]
  (doseq [repo package-repositories]
    (package-repository* repo (select-keys options [:instance-id])))
  (doseq [pkg packages]
    (package* pkg (select-keys options [:instance-id]))))

(defn install
  "Install package repositories and packages."
  [{:keys [instance-id]}]
  (let [{:keys [packages package-repositories]}
        (get-settings ::packages)]
    (doseq [repo package-repositories]
      (actions/package-repository repo))
    ;; TODO automatic package manager update of changed repo defintions
    (when (seq packages)
      (actions/all-packages packages))))

(defn server-spec
  "Return a server spec that will install packages, as specified
by calls to `package` and `package-repository`"
  [{:keys [packages package-repositories instance-id] :as options}]
  (api/server-spec
   :phases {:settings (api/plan-fn
                       (settings options))
            :install (api/plan-fn
                      (install (select-keys options [:instance-id])))}))
