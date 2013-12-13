(ns pallet.environment
  "The environments provide mechanisms for customising Pallet and
   Pallet crates according to externally determined criteria.

   An environment can be specified at the global, service, invocation and tag
   scopes.

   To provide a global default, specify an `:environment` key at the top level
   of `defpallet` in `~/.pallet/config.clj`.

   To provide a service specific default, specify an `:environment` key at the
   service level of `defpallet` in `~/.pallet/config.clj`.

   To provide a project specific default, define `pallet.config/environment`.

   To provide a specific environment when invoking `lift` or `converge`, pass an
   environment map using the `:environment` key.

   The merging of values between scopes is key specific, and is determined by
   `merge-key-algorithm`."
  (:require
   [clojure.core.incubator :refer [-?>]]
   [clojure.walk :as walk]
   [pallet.core.file-upload :as file-upload]
   [pallet.ssh.node-state :as node-state]
   [pallet.core.primitives :refer [phases-with-meta]]
   [pallet.core.session :refer [session]]
   [pallet.core.user :refer [make-user]]
   [pallet.environment-impl :refer [get-for]]
   [pallet.local.execute :as local]
   [pallet.map-merge :as map-merge]
   [pallet.map-merge :refer [merge-key]]
   [pallet.utils :as utils :refer [maybe-update-in total-order-merge]]))

(defprotocol Environment
  "A protocol for accessing an environment."
  (environment [_] "Returns an environment map"))

(defn pipeline
  [a b]
  (with-meta
    (fn merged-phases [& args] (apply a args) (apply b args))
    (merge (meta a) (meta b))))         ; TODO merge keys properly

(defmethod merge-key :merge-phases
  [_ _ val-in-result val-in-latter]
  (merge-with pipeline val-in-result val-in-latter))

(defmethod merge-key :total-ordering
  [_ _ val-in-result val-in-latter]
  (total-order-merge val-in-result val-in-latter))

(def
  ^{:doc
    "Map associating keys to merge algorithms. Specifies how environments are merged."}
  merge-key-algorithm
  {:phases :merge-phases
   :user :merge
   :image :merge
   :compute :replace
   :blobstore :replace
   :count :merge
   :algorithms :merge
   :executor :replace
   :middleware :replace
   :groups :merge-environments
   :roles :union
   :group-names :union
   :tags :merge-environments
   :install-plugins :concat
   ;; :executors :concat
   })


(def ^{:doc "node-specific environment keys"}
  node-keys [:image :phases])

(def standard-pallet-keys (keys merge-key-algorithm))

(def user-keys-to-shell-expand [:public-key-path :private-key-path])

(defn merge-environments
  "Returns a map that consists of the rest of the maps `conj`-ed onto
  the first.  If a key occurs in more than one map, the mapping(s)
  from the latter (left-to-right) will be combined with the mapping in
  the result by calling `(merge-key key val-in-result val-in-latter)`."
  [& maps]
  (apply map-merge/merge-keys merge-key-algorithm maps))

(defmethod map-merge/merge-key :merge-environments
  [key val-in-result val-in-latter]
  (merge-environments val-in-result val-in-latter))

(defn- eval-phase
  "Evaluate a phase definition."
  [phase]
  (if (or (list? phase) (instance? clojure.lang.Cons phase))
    (eval phase)
    phase))

(defn- eval-phases
  "Evaluate a phase map.  This will attempt to require any namespaces mentioned
   and will then read each phase definition."
  [phases]
  (walk/postwalk
   #(do
      (when (symbol? %)
        (when-let [n (namespace %)]
          (utils/find-var-with-require %)))
      %)
   phases)
  (zipmap (keys phases) (map eval-phase (vals phases))))

(defn- eval-algorithms
  "Evaluate an algorithm map.  This will attempt to require any namespaces
   mentioned and will then lookup each symbol to retrieve the specified
   var."
  [algorithms]
  (walk/postwalk
   #(or
     (when (and (symbol? %) (namespace %))
       (utils/find-var-with-require %))
      %)
   algorithms))

(defn- eval-action-options
  "Evaluate an action-option map.  This will construct action options
  from keywords, if present.
  e.g :file-uploader [:sftp {}]"
  [{:keys [file-backup file-checksum file-uploader] :as options}]
  (let [options (if (vector? file-uploader)
                  (assoc options
                    :file-uploader (apply file-upload/file-uploader
                                          file-uploader))
                  options)
        options (if (vector? file-checksum)
                  (assoc options
                    :file-checksum (apply node-state/file-checksum
                                          file-checksum))
                  options)
        options (if (vector? file-backup)
                  (assoc options
                    :file-backup (apply node-state/file-backup
                                          file-backup))
                  options)]
    options))

(defn shell-expand-keys
  "Shell-expand the values matching the specified keys"
  [user-map keys]
  (reduce
   (fn [m kwd]
     (if (kwd m)
       (update-in m [kwd] local/local-script-expand)
       m))
   user-map keys))

(defn eval-environment
  "Evaluate an environment literal.  This is used to replace certain keys with
   objects constructed from the map of values provided.  The keys that are
   evaluated are:
   - `:user`
   - `:phases`
   - `:algorithms`"
  [env-map]
  (let [env-map (if-let [user (shell-expand-keys
                               (:user env-map) user-keys-to-shell-expand)]
                  (if-let [username (:username user)]
                    (assoc
                        env-map :user
                        (make-user username user))
                    env-map)
                  env-map)
        env-map (if-let [phases (:phases env-map)]
                  (if (every? fn? (vals phases))
                    env-map
                    (assoc env-map :phases (eval-phases phases)))
                  env-map)
        env-map (if-let [algorithms (:algorithms env-map)]
                  (if (every? fn? (vals algorithms))
                    env-map
                    (assoc env-map :algorithms (eval-algorithms algorithms)))
                  env-map)
        env-map (if-let [action-options (:action-options env-map)]
                  (assoc env-map :algorithms (eval-action-options action-options))
                  env-map)]
    env-map))

(defn get-environment
  "Environment accessor."
  ([keys]
     (get-for (session) keys))
  ([keys default]
     (get-for (session) keys default)))

(defn group-with-environment
  "Add the environment to a group."
  [environment group]
  (merge-environments
   (maybe-update-in (select-keys environment node-keys)
                    [:phases] phases-with-meta {})
   group
   (maybe-update-in (-?> environment :groups group)
                    [:phases] phases-with-meta {})))
