(ns pallet.ssh.credentials
  "Functions for working with ssh credentials."
  (:require
   [clj-ssh.ssh :refer [generate-keypair keypair ssh-agent]]
   [clojure.java.io :refer [file]]
   [taoensso.timbre :refer [debugf error errorf warn]]))

(defn ssh-credential-status
  "Given a user map, returns a keyword indicating the credentials' status.

  - :not-found
  - :private-key-not-found
  - :public-key-not-found
  - :invalid-key
  - :valid-key"
  [{:keys [password private-key private-key-path public-key-path] :as user}]
  (cond
   password
   :valid-credential

   private-key
   (try
     (if (keypair (ssh-agent {}) user)
       :valid-credential
       :invalid-key)
     (catch Exception e
       (debugf e "Error when verifying keypair")
       :invalid-key))

   (and (not (.exists (file private-key-path)))
        (not (.exists (file public-key-path))))
   :not-found

   (and (.exists (file private-key-path))
        (not (.exists (file public-key-path))))
   :public-key-not-found

   (and (not (.exists (file private-key-path)))
        (.exists (file public-key-path)))
   :private-key-not-found

   :else
   (try
     (if (keypair (ssh-agent {}) user)
       :valid-credential
       :invalid-key)
     (catch Exception e
       (debugf e "Error when verifying keypair")
       :invalid-key))))

(defn generate-keypair-files
  "Generate keypair files for the given user and options"
  [{:keys [private-key-path public-key-path] :as user}
   {:keys [comment key-type key-size passphrase]
    :or {key-type :rsa key-size 2048}
    :as options}]
  {:pre [private-key-path public-key-path]}
  (generate-keypair
   (ssh-agent {}) key-type key-size passphrase
   :private-key-path private-key-path
   :public-key-path public-key-path
   :comment comment))

(defn ensure-ssh-credential
  "Given a user map, ensure credentials exist at the specified :private-key-path
  and :public-key-path.  Logs and prints any issues found with the credentials.


  Returns a keyword indicating the credentials' status.

  - :not-found
  - :private-key-not-found
  - :public-key-not-found
  - :invalid-key
  - :valid-key"
  [{:keys [private-key-path public-key-path] :as user}
   {:keys [comment key-type key-size passphrase] :as options}]
  (case (ssh-credential-status user)

    :invalid-key
    (throw (ex-info "Invalid key" {:user user}))

    :not-found
    (let [msg (format "keypair (%s, %s) does not exist"
                      public-key-path private-key-path)]
      (println msg)
      (warn msg)
      (generate-keypair-files user options))

    :public-key-not-found
    (let [msg (format
               "The public key path %s does not exist, but %s does."
               public-key-path private-key-path)]
      (println msg)
      (error msg)
      (throw (ex-info "Invalid public key path" {:user user})))

    :private-key-not-found
    (let [msg (format
               "The private key path %s does not exist, but %s does."
               private-key-path public-key-path)]
      (println msg)
      (error msg)
      (throw (ex-info "Invalid private key path" {:user user})))

    :valid-credential nil))
