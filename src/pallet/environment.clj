(ns pallet.environment
  "The environments provide mechanisms for customising Pallet and
   Pallet crates according to externally determined criteria.  It is a
   protocol, which is implemented on records to return a data map, an
   algorithm that allows merging these data maps, and access to these
   values in the plan state.

   In plan-fns, the environment should be considered read-only.

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
   [clojure.walk :as walk]
   [pallet.environment.protocols :as impl]
   [pallet.local.execute :as local]
   [pallet.map-merge :as map-merge :refer [merge-key]]
   [pallet.session :as session]
   [pallet.user :refer [make-user]]
   [pallet.utils :as utils :refer [total-order-merge]]))

;;; # Environment maps
(defn has-environment?
  "Predicate to test if x is capable of supplying an environment map."
  [x]
  (impl/has-environment? x))

(defn environment
  "Return an environment map from x."
  [x]
  {:pre [(has-environment? x)]}
  (impl/environment x))


;;; # Environment Merging

;;; Merging environments is implemented via map-merge, which looks up
;;; the key being merged in a map to determine the algorithm to use.

(def
  ^{:doc
    "Map specifying how environments are merged.  Associates keys to
  merge algorithms."}
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

;; TODO - is this used? (def standard-pallet-keys (keys merge-key-algorithm))

(defn merge-environments
  "Returns a map that consists of the rest of the maps merged onto the
  first.  If a key occurs in more than one map, the mapping(s) from
  the latter (left-to-right) will be combined with the mapping in the
  result by calling `(merge-key key val-in-result val-in-latter)`."
  [& maps]
  (apply map-merge/merge-keys merge-key-algorithm maps))

;;; ## Environment Specific Merge Algorithms
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

(defmethod merge-key :merge-environments
  [key val-in-result val-in-latter]
  (merge-environments val-in-result val-in-latter))

(defn- eval-phase
  "Evaluate a phase definition."
  [phase]
  (if (or (list? phase) (instance? clojure.lang.Cons phase))
    (eval phase)
    phase))

;;; # Eval of Environment Maps

;;; If an environment is read from a data souce, some of the data should be
;;; processed.

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

(def user-keys-to-shell-expand [:public-key-path :private-key-path])

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

;;; # Environment in the Plan-State

;;; The effective environment can be set and read.  This is so
;;; plan-fn's can access the environment with no knowledge about the
;;; scope at which they are invoked.

(defn ^:internal merge-environment
  "Merge an environment into the effective environment.  Returns a modified
  session."
  [session environment]
  (session/update-environment session merge-environments environment))

(defn get-environment
  "Retrieve the effective environment value at the path specified by keys.
   When no default value is specified, then raise an `:environment-not-found` if
   no environment value is set.

       (get-environment session [:p :a :d])"
  ([session keys]
     {:pre [(sequential? keys)]}
     (let [result (get-in (session/environment session) keys ::not-set)]
       (when (= ::not-set result)
         (throw
          (ex-info
           (format
            "Could not find keys %s in environment"
            (if (sequential? keys) (vec keys) keys))
           {:type :environment-not-found
            :key-not-set keys
            :environment (session/environment session)})))
       result))
  ([session keys default]
     (get-in (session/environment session) keys default)))
