(ns pallet.crate.automated-admin-user
  (:require
   [clojure.tools.logging :refer [debugf]]
   [pallet.actions :refer [package-manager user]]
   [pallet.crate.ssh-key :as ssh-key]
   [pallet.crate.sudoers :as sudoers]
   [pallet.plan :refer [defplan plan-fn]]
   [pallet.settings :refer [assoc-settings get-settings update-settings]]
   [pallet.spec :refer [server-spec]]
   [pallet.target :refer [admin-user]]
   [pallet.utils :refer [conj-distinct]]))

(def facility ::automated-admin-user)

(defn default-settings
  []
  {:install-sudo true
   :sudoers-instance-id nil})

(defn settings [session settings & {:keys [instance-id] :as options}]
  (assoc-settings session
                  facility
                  (merge (default-settings) settings)
                  options))

(defplan authorize-user-key
  "Authorise a single key, specified as a path or as a byte array."
  [session username path-or-bytes]
  (if (string? path-or-bytes)
    (ssh-key/authorize-key session username (slurp path-or-bytes))
    (ssh-key/authorize-key session username (String. ^bytes path-or-bytes))))

(defn default-sudoers-args
  [username]
  [{}
   {:default {:env_keep "SSH_AUTH_SOCK"}}
   {username {:ALL {:run-as-user :ALL :tags :NOPASSWD}}}])

(defplan create-admin-user
  "Builds a user for use in remote-admin automation. The user is given
  permission to sudo without password, so that passwords don't have to appear
  in scripts, etc."
  ([session]
     (let [user (admin-user session)]
       (debugf "create-admin-user for %s" (pr-str user))
       (create-admin-user
        session
        (:username user)
        (:public-key-path user))))
  ([session username]
     (let [user (admin-user session)]
       (create-admin-user session username (:public-key-path user))))
  ([session username & public-key-paths]
     (update-settings session
                      facility {}
                      update-in [:users]
                      conj-distinct {:username username
                                     :public-key-paths public-key-paths})
     (apply sudoers/sudoers session (default-sudoers-args username))))

(defplan configure
  "Creates users, and Writes the configuration file for sudoers."
  [session {:keys [instance-id sudoers-instance-id] :as options}]
  (let [{:keys [install-sudo sudoers-instance-id users]}
        (get-settings session facility options)]
    (when install-sudo
      (sudoers/install session {:instance-id sudoers-instance-id}))
    (doseq [{:keys [username public-key-paths]} users]
      (user session username {:create-home true :shell :bash})
      (doseq [kp public-key-paths]
        (authorize-user-key session username kp)))
    (sudoers/configure session {:instance-id sudoers-instance-id})))

(def
  ^{:doc "Convenience server spec to add the current admin-user on bootstrap."}
  with-automated-admin-user
  (server-spec
   :phases {:settings (plan-fn [session]
                        (debugf "with-automated-admin-user :settings")
                        (sudoers/settings session {})
                        (settings session {})
                        (create-admin-user session))
            :bootstrap (plan-fn [session]
                         (package-manager session :update)
                         (configure session {}))}))
