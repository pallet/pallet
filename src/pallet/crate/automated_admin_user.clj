(ns pallet.crate.automated-admin-user
  (:require
   [pallet.actions :refer [package-manager user]]
   [pallet.api :refer [plan-fn server-spec]]
   [pallet.crate :refer [admin-user assoc-settings defplan
                         get-settings update-settings]]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.sudoers :as sudoers]
   [pallet.utils :refer [conj-distinct]]))

(defn default-settings
  []
  {:install-sudo true
   :sudoers-instance-id nil})

(defn settings [settings & {:keys [instance-id] :as options}]
  (assoc-settings ::automated-admin-user
                  (merge (default-settings) settings)
                  options))

(defplan authorize-user-key
  "Authorise a single key, specified as a path or as a byte array."
  [username path-or-bytes]
  (if (string? path-or-bytes)
    (ssh-key/authorize-key username (slurp path-or-bytes))
    (ssh-key/authorize-key username (String. ^bytes path-or-bytes))))

(defn default-sudoers-args
  [username]
  [{}
   {:default {:env_keep "SSH_AUTH_SOCK"}}
   {username {:ALL {:run-as-user :ALL :tags :NOPASSWD}}}])

(defplan create-admin-user
  "Builds a user for use in remote-admin automation. The user is given
  permission to sudo without password, so that passwords don't have to appear
  in scripts, etc."
  ([]
     (let [user (admin-user)]
       (clojure.tools.logging/debugf "a-a-u for %s" user)
       (create-admin-user
        (:username user)
        (:public-key-path user))))
  ([username]
     (let [user (admin-user)]
       (create-admin-user username (:public-key-path user))))
  ([username & public-key-paths]
     (update-settings ::automated-admin-user {}
                      update-in [:users]
                      conj-distinct {:username username
                                     :public-key-paths public-key-paths})
     (apply sudoers/sudoers (default-sudoers-args username))))

(defplan configure
  "Creates users, and Writes the configuration file for sudoers."
  [{:keys [instance-id sudoers-instance-id] :as options}]
  (let [{:keys [install-sudo sudoers-instance-id users]}
        (get-settings ::automated-admin-user options)]
    (when install-sudo
      (sudoers/install {:instance-id sudoers-instance-id}))
    (doseq [{:keys [username public-key-paths]} users]
      (user username :create-home true :shell :bash)
      (doseq [kp public-key-paths]
        (authorize-user-key username kp))
      (sudoers/configure {:instance-id sudoers-instance-id}))))

(def
  ^{:doc "Convenience server spec to add the current admin-user on bootstrap."}
  with-automated-admin-user
  (server-spec
   :phases {:settings (plan-fn
                       (sudoers/settings {})
                       (settings {}))
            :bootstrap (plan-fn
                        (package-manager :update)
                        (create-admin-user)
                        (configure {}))}))
