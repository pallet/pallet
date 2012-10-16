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
   [pallet.common.deprecate :as deprecate]
   [pallet.local.execute :as local]
   [pallet.map-merge :as map-merge]
   [pallet.utils :as utils]
   [clojure.set :as set]
   [clojure.tools.logging :as logging]
   [clojure.walk :as walk])
  (:use
   [clojure.core.incubator :only [-?>]]
   [pallet.core.user :only [make-user]]
   [slingshot.slingshot :only [throw+]]))

(defprotocol Environment
  "A protocol for accessing an environment."
  (environment [_] "Returns an environment map"))

(def
  ^{:doc
    "Map associating keys to merge algorithms. Specifies how environments are merged."}
  merge-key-algorithm
  {:phases :merge-comp
   :user :merge
   :image :merge
   :compute :replace
   :blobstore :replace
   :count :merge
   :algorithms :merge
   :executor :replace
   :middleware :replace
   :groups :merge-environments
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
                  env-map)]
    env-map))

(defn get-for
  "Retrieve the environment value at the path specified by keys.
   When no default value is specified, then raise an `:environment-not-found` if
   no environment value is set.

       (get-for {:p {:a {:b 1} {:d 2}}} [:p :a :d])
       ;=> 2"
  ([session keys]
     {:pre [(sequential? keys)]}
     (let [result (get-in (:environment session) keys ::not-set)]
       (when (= ::not-set result)
         (throw+
          {:type :environment-not-found
           :message (format
                     "Could not find keys %s in session :environment"
                     (if (sequential? keys) (vec keys) keys))
           :key-not-set keys}))
       result))
  ([session keys default]
     (get-in (:environment session) keys default)))

(defn get-environment
  "Monadic environment accessor"
  ([keys]
     (fn get-env [session]
       [(get-for session keys) session]))
    ([keys default]
     (fn get-env [session]
       [(get-for session keys default) session])))

(defn session-with-environment
  "Returns an updated `session` map, containing the keys for the specified
   `environment` map.

   When session includes a `:server` value, then the `:server` value is
   treated as an environment, and merge with any environment in the
   `environment`'s `:groups` key.

   The node-specific environment keys are `:images` and `:phases`."
  [session environment]
  (when (:tags environment)
    (deprecate/warn
     (str "Use of :tags key in the environment is deprecated. "
          "Please change to use :groups.")))
  (let [group (or (-> session :server :group-name)
                  (-> session :group :group-name))
        session (merge
                 session
                 (merge-environments
                  (->
                   environment
                   (select-keys standard-pallet-keys)
                   (utils/dissoc-keys (conj node-keys :groups :tags)))
                  (when group
                    (->
                     (-> environment :groups group)
                     (select-keys standard-pallet-keys)
                     (utils/dissoc-keys (conj node-keys :groups :tags))))))
        session (assoc-in session [:environment]
                          (utils/dissoc-keys environment node-keys))
        session (if (:server session)
                  (let [group-name (-> session :server :group-name)]
                    (assoc session
                      :server (merge-environments
                               (:server session)
                               (select-keys environment node-keys)
                               (-?> environment :tags group-name) ; deprecated
                               (-?> environment :groups group-name))))
                  session)
        session (if (:group session)
                  (let [group (-> session :group :group-name)]
                    (assoc session
                      :group (merge-environments
                              (:group session)
                              (select-keys environment node-keys)
                              (-?> environment :tags group) ; deprecated
                              (-?> environment :groups group))))
                  session)
        ;; session (assoc session
        ;;           :executor (reduce
        ;;                      merge
        ;;                      (map execute/executor-map (:executors session))))
        ]
    ;; (track-executors (:executors session))
    session))
