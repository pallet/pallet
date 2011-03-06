(ns pallet.environment
  "The environment provide a mechanism for customising pallet and
   pallet crates according to some externally determined criteria.

   An environment can be specified at the global, service, invocation and tag
   scopes.

   To provide a global default, specify an :environment key at the top level
   of `defpallet` in `~/.pallet/config.clj`.

   To provide a service spevific default, specify an :environment key at the
   service level of `defpallet` in `~/.pallet/config.clj`.

   To provide a project specific default, define `pallet.config/environment`.

   To provide a specific environment when invoking lift or converge, pass an
   environment map using the `:environment` key.

   The merging of values between scopes is key specific, and is determined by
   `merge-key-algorithm`."
  (:require
   [pallet.utils :as utils]
   [clojure.contrib.condition :as condition]
   [clojure.contrib.logging :as logging]
   [clojure.walk :as walk])
  (:use
   [clojure.contrib.core :only [-?>]]))

(defprotocol Environment
  "A protocol for accessing an environment."
  (environment [_] "Returns an environment map"))

(def
  ^{:doc
    "Map from key to merge algorithm. Specifies how environments are merged."}
  merge-key-algorithm
  {:phases :merge-comp
   :user :merge
   :image :merge
   :compute :replace
   :blobstore :replace
   :count :merge
   :algorithms :merge
   :middleware :replace
   :tags :merge-environments})

(defmulti merge-key
  "Merge function that dispatches on the map entry key"
  (fn [key val-in-result val-in-latter]
    (merge-key-algorithm key :replace)))

(defn merge-environments
  "Returns a map that consists of the rest of the maps conj-ed onto
  the first.  If a key occurs in more than one map, the mapping(s)
  from the latter (left-to-right) will be combined with the mapping in
  the result by calling (merge-key key val-in-result val-in-latter)."
  [& maps]
  (when (some identity maps)
    (let [merge-entry (fn [m e]
                        (let [k (key e) v (val e)]
                          (if (contains? m k)
                            (assoc m k (merge-key k (get m k) v))
                            (assoc m k v))))
          merge2 (fn [m1 m2]
                   (reduce merge-entry (or m1 {}) (seq m2)))]
      (reduce merge2 maps))))

(defmethod merge-key :replace
  [key val-in-result val-in-latter]
  val-in-latter)

(defmethod merge-key :merge
  [key val-in-result val-in-latter]
  (merge val-in-result val-in-latter))

(defmethod merge-key :merge-comp
  [key val-in-result val-in-latter]
  (merge-with comp val-in-latter val-in-result))

(defmethod merge-key :merge-environments
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

(defn eval-environment
  "Evaluate an environment literal.  This is used to replace certain keys with
   objects constructed from the map of values provided.  The keys that are
   evaluated are:
   - :user"
  [env-map]
  (let [env-map (if-let [user (:user env-map)]
                  (assoc
                      env-map :user
                      (apply utils/make-user
                             (:username user) (mapcat identity user)))
                  env-map)
        env-map (if-let [phases (:phases env-map)]
                  (assoc env-map :phases (eval-phases phases))
                  env-map)
        env-map (if-let [algorithms (:algorithms env-map)]
                  (assoc env-map :algorithms (eval-algorithms algorithms))
                  env-map)]
    env-map))

(defn get-for
  "Retrieve the environment value at the path specified by keys.
   When no default value is specified, then raise a :environment-not-found if no
   environment value is set.

       (get-for {:p {:a {:b 1} {:d 2}}} [:p :a :d])
         => 2"
  ([request keys]
     (let [result (get-in (:environment request) keys ::not-set)]
       (when (= ::not-set result)
         (condition/raise
          :type :environment-not-found
          :message (format
                    "Could not find keys %s in request :environment"
                    (if (sequential? keys) (vec keys) keys))
          :key-not-set keys))
       result))
  ([request keys default]
       (get-in (:environment request) keys default)))

(def ^{:doc "node specific environment keys"}
  node-keys [:image :phases])

(defn request-with-environment
  "Returns an updated `request` map, containing the keys for the specified
   `environment` map.

   When request includes a :server value, then the :server value is
   treated as an environment, and merge with any environment in the
   `environment`'s :tags key.

   The node-specific environment keys are :images and :phases."
  [request environment]
  (let [request (merge
                 request
                 (utils/dissoc-keys environment (conj node-keys :tags)))]
    (if (:server request)
      (let [tag (-> request :server :tag)]
        (assoc request
          :server (merge-environments
                   (:server request)
                   (select-keys environment node-keys)
                   (-?> environment :tags tag))))
      request)))
