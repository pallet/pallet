(ns pallet.core
"Core functionality is provided in `lift` and `converge`.

- node           :: A node in the compute service
- node-spec      :: A specification for a node. The node-spec provides an image
                    hardware, location and network template for starting new
                    nodes.
- server-spec    :: A specification for a server. This is a map of phases and
                    a default node-spec. A server-spec has the following keys
                    :phase, :packager and node-spec keys.
- group-spec     :: A group of identically configured nodes, represented as a
                    map with :group-name, :count and server-spec keys.
                    The group-name is used to link running nodes to their
                    configuration (via pallet.node.Node/group-name)
- group          :: A group of identically configured nodes, represented as a
                    group-spec, together with the servers that are running
                    for that group-spec.
- group name     :: The name used to identify a group.
- server         :: A map used to descibe the node, image, etc of a single
                    node running as part of a group. A server has the
                    following keys :group-name, :node, :node-id and server-spec
                    keys.
- phase list     :: A list of phases to be used
- action plan    :: A list of actions that should be run."
  {:author "Hugo Duncan"}
  (:require
   [pallet.action :as action]
   [pallet.action-plan :as action-plan]
   [pallet.blobstore :as blobstore]
   [pallet.common.deprecate :as deprecate]
   [pallet.common.logging.logutils :as logutils]
   [pallet.common.map-utils :as map-utils]
   [pallet.common.resource :as resource]
   [pallet.compute :as compute]
   [pallet.environment :as environment]
   [pallet.execute :as execute]
   [pallet.futures :as futures]
   [pallet.map-merge :as map-merge]
   [pallet.node :as node]
   [pallet.parameter :as parameter]
   [pallet.phase :as phase]
   [pallet.script :as script]
   [pallet.thread-expr :as thread-expr]
   [pallet.utils :as utils]
   [clojure.tools.logging :as logging]
   [clojure.set :as set]
   [clojure.string :as string])
  (:use
   [clojure.core.incubator :only [-?>]]))

;; slingshot version compatibility
(try
  (use '[slingshot.slingshot :only [throw+]])
  (catch Exception _
    (use '[slingshot.core :only [throw+]])))

(let [v (atom nil)]
  (defn version
    "Returns the pallet version."
    []
    (or
     @v
     (reset! v (System/getProperty "pallet.version"))
     (reset! v (if-let [version (resource/slurp "pallet-version")]
                       (string/trim version))))))

;; Set the agent string for http requests.
(. System setProperty "http.agent"
   (str "Pallet " (version)))

(defmacro with-admin-user
  "Specify the admin user for running remote commands.  The user is specified
   either as pallet.utils.User record (see the pallet.utils/make-user
   convenience fn) or as an argument list that will be passed to make-user.

   This is mainly for use at the repl, since the admin user can be specified
   functionally using the :user key in a lift or converge call, or in the
   environment."
  {:arglists
   '([user & body]
     [[username & {:keys [public-key-path private-key-path passphrase password
                          sudo-password no-sudo] :as options}] & body])}
  [user & exprs]
  `(let [user# ~user]
     (binding [utils/*admin-user* (if (utils/user? user#)
                                    user#
                                    (apply utils/make-user user#))]
       ~@exprs)))

(defn admin-user
  "Set the root binding for the admin user.
   The user arg is a map as returned by make-user, or a username.  When passing
   a username the options can be specified as in `pallet.utils/make-user`.

   This is mainly for use at the repl, since the admin user can be specified
   functionally using the :user key in a lift or converge call, or in the
   environment."
  {:arglists
   '([user]
     [username & {:keys [public-key-path private-key-path passphrase
                         password sudo-password no-sudo] :as options}])}
  [user & options]
  (alter-var-root
   #'utils/*admin-user*
   #(identity %2)
   (if (string? user)
     (apply utils/make-user user options)
     user)))

(def ^{:doc "Vector of keywords recognised by node-spec"
       :private true}
  node-spec-keys [:image :hardware :location :network])

(defn node-spec
  "Create a node-spec.

   Defines the compute image and hardware selector template.

   This is used to filter a cloud provider's image and hardware list to select
   an image and hardware for nodes created for this node-spec.

   :image     a map descirbing a predicate for matching an image:
              os-family os-name-matches os-version-matches
              os-description-matches os-64-bit
              image-version-matches image-name-matches
              image-description-matches image-id

   :location  a map describing a predicate for matching location:
              location-id
   :hardware  a map describing a predicate for matching harware:
              min-cores min-ram smallest fastest biggest architecture
              hardware-id
   :network   a map for network connectivity options:
              inbound-ports
   :qos       a map for quality of service options:
              spot-price enable-monitoring"
  [& {:keys [image hardware location network qos] :as options}]
  {:pre [(or (nil? image) (map? image))]}
  (vary-meta options assoc :type ::node-spec))

(def
  ^{:doc
    "Map from key to merge algorithm. Specifies how specs are merged."}
  merge-spec-algorithm
  {:phases :merge-comp
   :roles :merge-union})

(defn- merge-specs
  "Merge specs, using comp for :phases"
  [a b]
  (map-merge/merge-keys merge-spec-algorithm a b))

(defn- extend-specs
  "Merge in the inherited specs"
  [spec inherits]
  (if inherits
    (merge-specs
     (if (map? inherits) inherits (reduce merge-specs inherits))
     spec)
    spec))

(defn server-spec
  "Create a server-spec.

   - :phases a hash-map used to define phases. Standard phases are:
     - :bootstrap    run on first boot of a new node
     - :configure    defines the configuration of the node
   - :extends        takes a server-spec, or sequence thereof, and is used to
                     inherit phases, etc.
   - :roles          defines a sequence of roles for the server-spec
   - :node-spec      default node-spec for this server-spec
   - :packager       override the choice of packager to use"
  [& {:keys [phases packager node-spec extends roles]
      :as options}]
  (->
   (or node-spec {})                    ; ensure we have a map and not nil
   (merge options)
   (thread-expr/when-> roles
           (update-in [:roles] #(if (keyword? %) #{%} (into #{} %))))
   (extend-specs extends)
   (dissoc :extends :node-spec)
   (vary-meta assoc :type ::server-spec)))

(defn group-spec
  "Create a group-spec.

   `name` is used for the group name, which is set on each node and links a node
   to it's node-spec

   - :extends  specify a server-spec, a group-spec, or sequence thereof,
               and is used to inherit phases, etc.

   - :phases used to define phases. Standard phases are:
     - :bootstrap    run on first boot of a new node
     - :configure    defines the configuration of the node.

   - :count    specify the target number of nodes for this node-spec
   - :packager override the choice of packager to use
   - :node-spec      default node-spec for this server-spec"
  [name
   & {:keys [extends count image phases packager node-spec roles] :as options}]
  {:pre [(or (nil? image) (map? image))]}
  (->
   node-spec
   (merge options)
   (thread-expr/when-> roles
           (update-in [:roles] #(if (keyword? %) #{%} (into #{} %))))
   (extend-specs extends)
   (dissoc :extends :node-spec)
   (assoc :group-name (keyword name))
   (vary-meta assoc :type ::group-spec)))

(defn expand-cluster-groups
  "Expand a node-set into its groups"
  [node-set]
  (cond
   (sequential? node-set) (mapcat expand-cluster-groups node-set)
   (map? node-set) (if-let [groups (:groups node-set)]
                     (mapcat expand-cluster-groups groups)
                     [node-set])
   :else [node-set]))

(defn expand-group-spec-with-counts
  "Expand a converge node spec into its groups"
  ([node-set spec-count]
     (letfn [(*1 [x y] (* (or x 1) y))
             (scale-spec [spec factor]
               (update-in spec [:count] *1 factor))
             (set-spec [node-spec]
               (mapcat
                (fn [[node-spec spec-count]]
                  (if-let [groups (:groups node-spec)]
                    (expand-group-spec-with-counts groups spec-count)
                    [(assoc node-spec :count spec-count)]))
                node-set))]
       (cond
        (sequential? node-set) (mapcat
                                #(expand-group-spec-with-counts % spec-count)
                                node-set)
        (map? node-set) (if-let [groups (:groups node-set)]
                          (let [spec (scale-spec node-set spec-count)]
                            (mapcat
                             #(expand-group-spec-with-counts % (:count spec))
                             groups))
                          (if (:group-name node-set)
                            [(scale-spec node-set spec-count)]
                            (set-spec node-spec)))
        :else [(scale-spec node-set spec-count)])))
  ([node-set] (expand-group-spec-with-counts node-set 1)))

(defn cluster-spec
  "Create a cluster-spec.

   `name` is used as a prefix for all groups in the cluster.

   - :groups    specify a sequence of groups that define the cluster

   - :extends   specify a server-spec, a group-spec, or sequence thereof
                for all groups in the cluster

   - :phases    define phases on all groups.

   - :node-spec default node-spec for the nodes in the cluster

   - :roles     roles for the group-spec"
  [cluster-name
   & {:keys [extends groups phases node-spec environment] :as options}]
  (->
   options
   (update-in [:groups]
              (fn [group-specs]
                (map
                 (fn [group-spec]
                   (->
                    node-spec
                    (merge (dissoc group-spec :phases))
                    (update-in
                     [:group-name]
                     #(keyword (str (name cluster-name) "-" (name %))))
                    (update-in
                     [:environment]
                     environment/merge-environments environment)
                    (extend-specs extends)
                    (extend-specs [{:phases phases}])
                    (extend-specs [(select-keys group-spec [:phases])])))
                 (expand-group-spec-with-counts group-specs 1))))
   (dissoc :extends :node-spec)
   (assoc :cluster-cluster-name (keyword cluster-name))
   (vary-meta assoc :type ::cluster-spec)))

(defn make-node
  "Create a node definition.  See defnode."
  {:deprecated "0.5.0"}
  [name image & {:as phase-map}]
  (deprecate/deprecated
   (str
    "pallet.core/make-node is deprecated. "
    "See group-spec, server-spec and node-spec in pallet.core."))
  {:pre [(or (nil? image) (map? image))]}
  (->
   {:group-name (keyword name)
    :image image}
   (thread-expr/when-not->
    (empty? phase-map)
    (assoc :phases phase-map))))

(defn name-with-attributes
  "Modified version, of that found in contrib, to handle the image map."
  [name macro-args]
  (let [[docstring macro-args] (if (string? (first macro-args))
                                 [(first macro-args) (next macro-args)]
                                 [nil macro-args])
        [attr macro-args] (if (and (map? (first macro-args))
                                   (map? (first (next macro-args))))
                            [(first macro-args) (next macro-args)]
                            [{} macro-args])
        attr (if docstring
               (assoc attr :doc docstring)
               attr)
        attr (if (meta name)
               (conj (meta name) attr)
               attr)]
    [(with-meta name attr) macro-args]))

(defmacro defnode
  "Define a node type.  The name is used for the group name.

   image defines the image selector template.  This is a vector of keyword or
          keyword value pairs that are used to filter the image list to select
          an image.
   Options are used to define phases. Standard phases are:
     :bootstrap    run on first boot
     :configure    defines the configuration of the node."
  {:arglists ['(tag doc-str? attr-map? image & phasekw-phasefn-pairs)]
   :deprecated "0.5.0"}
  [group-name & options]
  (let [[group-name options] (name-with-attributes group-name options)]
    `(do
       (deprecate/deprecated-macro
        ~&form
        (str
         "pallet.core/defnode is deprecated. See group-spec, server-spec and "
         "node-spec in pallet.core"))
       (def ~group-name (make-node '~(name group-name) ~@options)))))

(defn- add-session-keys-for-0-4-compatibility
  "Add target keys for compatibility.
   This function adds back deprecated keys"
  [session]
  (-> session
      (assoc :node-type (:group session))
      (assoc :target-packager (-> session :server :packager))
      (assoc :target-node (-> session :server :node))))

(defn show-target-keys
  "Middleware that is useful in debugging."
  [handler]
  (fn [session]
    (logging/infof
     "TARGET KEYS :phase %s :node-id %s :group-name %s :packager %s"
     (:phase session)
     (-> session :server :node-id)
     (-> session :server :group-name)
     (-> session :server :packager))
    (handler session)))


;;; executor

(defn- executor [session f action-type location]
  (let [exec-fn (get-in session [:executor action-type location])]
    (when-not exec-fn
      (throw+
       {:type :missing-executor-fn
        :fn-for [action-type location]
        :message (format
                  "Missing executor function for %s %s"
                  action-type location)}))
    (exec-fn session f)))

(let [raise (fn [message]
              (fn [_ _]
                (throw+ {:type :executor-error :message message})))]
  (def ^{:doc "Default executor map"}
    default-executors
    {:script/bash
     {:origin execute/bash-on-origin
      :target (raise
               (str ":script/bash on :target not implemented.\n"
                    "Add middleware to enable remote execution."))}
     :fn/clojure
     {:origin execute/clojure-on-origin
      :target (raise ":fn/clojure on :target not supported")}
     :transfer/to-local
     {:origin (raise
               (str ":transfer/to-local on :origin not implemented.\n"
                    "Add middleware to enable transfers."))
      :target (raise ":transfer/to-local on :target not supported")}
     :transfer/from-local
     {:origin (raise
               (str ":transfer/to-local on :origin not implemented.\n"
                    "Add middleware to enable transfers."))
      :target (raise ":transfer/from-local on :target not supported")}}))

;;; bootstrap functions
(defn- bootstrap-script
  [session]
  {:pre [(get-in session [:group :image :os-family])
         (get-in session [:group :packager])]}
  (let [error-fn (fn [message]
                   (fn [_ _]
                     (throw+
                      {:type :booststrap-contains-non-remote-actions
                       :message message})))
        [result session] (->
                          session
                          (assoc
                              :phase :bootstrap
                              :target-type :node
                              :target-id :bootstrap-id
                              :server (assoc (:group session)
                                        :node-id :bootstrap-id))
                          (assoc-in
                           [:executor :script/bash :target]
                           execute/echo-bash)
                          (assoc-in
                           [:executor :transfer/to-local :origin]
                           (error-fn "Bootstrap can not contain transfers"))
                          (assoc-in
                           [:executor :transfer/from-local :origin]
                           (error-fn "Bootstrap can not contain transfers"))
                          (assoc-in
                           [:executor :fn/clojure :origin]
                           (error-fn "Bootstrap can not contain local actions"))
                          add-session-keys-for-0-4-compatibility
                          action-plan/build-for-target
                          action-plan/translate-for-target
                          (action-plan/execute-for-target
                           executor
                           (environment/get-for
                            session [:algorithms :execute-status-fn])))]
    (when-let [error (some :error result)]
      (throw+ error))
    (string/join \newline result)))


(defn log-session
  "Log the session state"
  [msg]
  (fn [session]
    (logging/infof "%s Session is %s" msg session)
    session))

(defn log-message
  "Log the message"
  [msg]
  (fn [session]
    (logging/infof "%s" msg)
    session))

(defn- log-nodes
  "Log the node lists in the session state"
  [msg]
  (fn [session]
    (logging/infof
     "%s nodes  %s with %s old nodes"
     msg
     (pr-str
      (select-keys
       session [:all-nodes :selected-nodes :new-nodes]))
     (count (:old-nodes session)))
    session))

(defn- apply-environment
  "Apply the effective environment"
  [session]
  (environment/session-with-environment
    session
    (environment/merge-environments
     (:environment session)
     (environment/eval-environment (-> session :server :environment)))))

(defn translate-action-plan
  [handler]
  (fn [session]
    (handler (action-plan/translate-for-target session))))

(defn middleware-handler
  "Build a middleware processing pipeline from the specified middleware.
   The result is a middleware."
  [handler]
  (fn [session]
    ((reduce #(%2 %1) handler (:middleware session)) session)))

(defn- execute
  "Execute the action plan"
  [session]
  (action-plan/execute-for-target
   session executor
   (environment/get-for session [:algorithms :execute-status-fn])))

;;; middleware

(defn stop-execution-on-error
  ":execute-status-fn algorithm to stop execution on an error"
  [result flag]
  (if (= flag :continue)
    (if (:error result)
      :stop
      flag)
    flag))

(defn raise-on-error
  "Middleware that raises a condition on an error."
  [handler]
  (fn [session]
    (let [[results session] (handler session)
          errors (seq (filter :error results))
          error (:error (first errors))]
      (if errors
        ;; &throw-context is used by slingshot to get the cause
        (let [&throw-context {:throwable (:cause error)}]
          (logging/errorf "errors found %s" (vec (map :error errors)))
          (throw+
           (assoc error :all-errors errors)
           "Error prevented completion of phase: %s"
           (:message error)))
        [results session]))))

(def ^{:dynamic true} *middleware*
  [translate-action-plan
   execute/ssh-user-credentials
   execute/execute-with-ssh
   raise-on-error])

(defmacro with-middleware
  "Wrap node execution in the given middleware. A middleware is a function of
   one argument (a handler function, that is the next middleware to call) and
   returns a dunction of one argument (the session map).  Middleware can be
   composed with the pipe macro."
  [f & body]
  `(binding [*middleware* ~f]
     ~@body))

;;; results
(defn- reduce-phase-results
  "Combine the phase execution results."
  [session results]
  (reduce
   (fn reduce-phase-results-fn [session [result req :as arg]]
     (let [target-id (-> req :target-id)
           param-keys [:parameters]]
       (->
        session
        (assoc-in [:results target-id (:phase req)] result)
        (update-in
         param-keys
         (fn merge-params [p]
           (map-utils/deep-merge-with
            (fn merge-params-fn [x y] (or y x)) p (get-in req param-keys)))))))
   session
   results))

;;; action plan
(defn- plan-for-server
  "Build an action plan for the specified server."
  [session server]
  {:pre [(:node server) (:node-id server)]}
  (logutils/with-context [:target (node/primary-ip (:node server))]
    (logging/debugf "p-f-s server environment %s" (:environment server))
    (action-plan/build-for-target
     (->
      session
      (assoc :server server)
      add-session-keys-for-0-4-compatibility
      (assoc :target-id (:node-id server))
      (environment/session-with-environment
        (environment/merge-environments
         (:environment session)
         (:environment server)))))))

(defn- plan-for-servers
  "Build an action plan for the specified servers."
  [session servers]
  (reduce plan-for-server session servers))

(defn- plan-for-groups
  "Build an invocation map for specified node-type map."
  [session groups]
  (reduce
   (fn [session group]
     (logutils/with-context [:group (:group-name group)]
       (plan-for-servers (assoc session :group group) (:servers group))))
   session groups))

(defn- plan-for-phases
  "Build an invocation map for specified phases and nodes.
   This allows configuration to be accumulated in the session parameters."
  [session]
  (reduce
   (fn [session phase]
     (logutils/with-context [:phase phase]
       (plan-for-groups (assoc session :phase phase) (:groups session))))
   session (:phase-list session)))


(defn- plan-for-group-phase
  "Build an invocation map for specified groups map."
  [session groups]
  (reduce
   (fn [session group]
     (logutils/with-context [:group (:group-name group)]
       (action-plan/build-for-target
        (->
         session
         (assoc :target-id (:group-name group) :group group)
         (environment/session-with-environment
           (environment/merge-environments
            (:environment session)
            (:environment group)))))))
   session groups))

(defmulti plan-for-target :target-type)

(defmethod plan-for-target :node
  [session]
  (plan-for-groups session (:groups session)))

(defmethod plan-for-target :group
  [session]
  (plan-for-group-phase session (:groups session)))

;;; Phase application
(defn has-phase?
  [session]
  (let [phase (:phase session)]
    (action-plan/phase-for-target
     (assoc session :phase (or (phase/subphase-for phase) phase)))))

(defn- apply-phase-to-node
  "Apply a phase to a node session"
  [session]
  {:pre [(:server session) (:phase session)]}
  (logging/debugf
   "apply-phase-to-node: phase %s group %s target %s"
   (:phase session)
   (-> session :group :group-name)
   (node/primary-ip (-> session :server :node)))
  (logutils/with-context [:target (node/primary-ip
                                   (-> session :server :node))
                          :phase (:phase session)
                          :group (-> session :group :group-name)]
    ((middleware-handler execute)
     (->
      session
      apply-environment
      add-session-keys-for-0-4-compatibility))))

(defn- apply-phase-to-group
  "Apply a phase to a group"
  [session]
  {:pre [(:group session) (:phase session)]}
  (logging/debugf
   "apply-phase-to-group: phase %s group %s"
   (:phase session)
   (-> session :group :group-name))
  (logutils/with-context [:phase (:phase session)
                          :group (-> session :group :group-name)]
    ((middleware-handler execute)
     session)))

(defmulti sequential-apply-phase-to-target :target-type)

(defmethod sequential-apply-phase-to-target :node
  [session]
  (logging/infof
   "sequential-apply-phase-to-target :node  %s for %s with %d nodes"
   (:phase session)
   (-> session :group :group-name)
   (count (-> session :group :servers)))
  (for [server (-> session :group :servers)
        :when (not (:invoke-only server))
        :let [session (assoc session
                        :server server :target-id (:node-id server))]
        :when (has-phase? session)]
    (apply-phase-to-node session)))

(defmethod sequential-apply-phase-to-target :group
  [session]
  (logging/infof
   "sequential-apply-phase-to-target :group  %s for %s"
   (:phase session) (-> session :group :group-name))
  (let [session (assoc session :target-id (-> session :group :group-name))]
    (when (has-phase? session)
      [(apply-phase-to-group session)])))

(defn sequential-apply-phase
  "Apply a phase to a sequence of nodes"
  [session]
  (logging/infof
   "sequential-apply-phase %s for %s"
   (:phase session) (-> session :group :group-name))
  (sequential-apply-phase-to-target session))

(defmulti parallel-apply-phase-to-target :target-type)

(defmethod parallel-apply-phase-to-target :node
  [session]
  (logging/infof
   "parallel-apply-phase-to-target :node  %s for %s with %d nodes"
   (:phase session)
   (-> session :group :group-name)
   (count (-> session :group :servers)))
  (for [server (-> session :group :servers)
        :when (not (:invoke-only server))
        :let [session (assoc session
                        :server server :target-id (:node-id server))]
        :when (has-phase? session)]
    (future (apply-phase-to-node session))))

(defmethod parallel-apply-phase-to-target :group
  [session]
  (logging/infof
   "parallel-apply-phase-to-target :group  %s for %s"
   (:phase session) (-> session :group :group-name))
  (let [session (assoc session :target-id (-> session :group :group-name))]
    (when (has-phase? session)
      [(future (apply-phase-to-group session))])))

(defn parallel-apply-phase
  "Apply a phase to a sequence of nodes"
  [session]
  (logging/infof
   "parallel-apply-phase %s for %s"
   (:phase session) (-> session :group :group-name))
  (futures/add (parallel-apply-phase-to-target session)))

(defn- ensure-phase [phases phase-kw]
  (if (some #{phase-kw} phases)
    phases
    (concat [phase-kw] phases)))

(defn sequential-lift
  "Sequential apply the phases."
  [session]
  (apply
   concat
   (for [group (:groups session)]
     (sequential-apply-phase (assoc session :group group)))))

(defn parallel-lift
  "Apply the phases in sequence, to nodes in parallel."
  [session]
  (->>
   (for [group (:groups session)]
     (parallel-apply-phase (assoc session :group group)))
   (reduce concat [])
   doall                        ; make sure we start all futures before deref
   (map deref)                  ; make sure all nodes complete before next phase
   doall))                      ; make sure we force the deref

(defn lift-phase
  "Lift nodes in target-node-map for the specified phases.

   Builds the commands for the phase, then executes pre-phase, phase, and
   after-phase"
  [session]
  (let [lift-fn (environment/get-for session [:algorithms :lift-fn])
        phase (:phase session)]
    (reduce
     (fn [session sub-phase]
       (let [session (->
                      session
                      (assoc :phase phase)
                      plan-for-target
                      (assoc :phase sub-phase))]
         (reduce-phase-results session (lift-fn session))))
     session
     (phase/all-phases-for-phase phase))))

(defn lift-nodes
  "Lift nodes in target-node-map for the specified phases."
  [session]
  (logging/infof
   "lift-nodes phases %s, groups %s"
   (vec (:phase-list session))
   (vec (map :group-name (:groups session))))
  (reduce
   (fn [session phase]
     (->
      session
      (assoc :phase phase)
      plan-for-target
      lift-phase))
   (assoc session :target-type :node)
   (:phase-list session)))

(defn- lift-group-phase
  [session]
  (logging/infof
   "lift-group-phase phases %s, groups %s"
   (vec (:phase-list session))
   (vec (map :group-name (:groups session))))
  (reduce
   (fn [session phase]
     (->
      session
      (assoc :phase phase)
      plan-for-target
      lift-phase))
   (assoc session :target-type :group)
   (:phase-list session)))

;;; node create/destroy

(defn- create-nodes
  "Create count nodes based on the template for the group.
   Returns a map with updated server node lists."
  [session]
  {:pre [(map? (:group session))]}
  (let [group (:group session)]
    (when-not (seq (-> group :image))
      (throw+
       {:message (format
                  "No :image specified in node-spec for group %s"
                  (:group-name group))
        :group (:group session)
        :type :pallet/could-not-start-new-nodes}))
    (logging/infof
     "Starting %s nodes for %s, os-family %s"
     (:delta-count group) (:group-name group) (-> group :image :os-family)))
  (let [compute (:compute session)
        count (-> session :group :delta-count)
        session (update-in session [:group]
                           #(compute/ensure-os-family compute %))
        session (assoc-in session [:group :packager]
                          (compute/packager (-> session :group :image)))
        init-script (bootstrap-script session)
        _ (logging/tracef "Bootstrap script:\n%s" init-script)
        new-nodes (compute/run-nodes
                   compute (:group session) count (:user session) init-script
                   (-> session :environment :provider-options))]
    (when-not (seq new-nodes)
      (throw+
       {:message "No additional nodes could be started"
        :group (:group session)
        :type :pallet/could-not-start-new-nodes}))
    (->
     session
     (assoc-in [:groups-new-nodes (-> session :group :group-name)] new-nodes)
     (update-in [:new-nodes] concat new-nodes))))

(defn- servers-to-remove
  "Finds the specified number of nodes to be removed from the given group.
   Nodes are selected at random. Update the :groups-with-servers-to-remove key
   of the session."
  [group]
  (let [destroy-count (- (:delta-count group 0))]
    (if (pos? destroy-count)
      (do
        (logging/infof
         "Select %s nodes for destruction in group %s"
         destroy-count (:group-name group))
        (assoc group
          :servers-to-remove (take destroy-count (:servers group))))
      group)))

(defn- remove-nodes
  "Destroys the specified number of nodes with the given group.  Nodes are
   selected at random. Returns a map containing removed nodes."
  [session]
  (let [compute (:compute session)
        group (:group session)
        servers (:servers-to-remove group)
        nodes (map :node servers)]
    (logging/infof
     "destroying %s nodes for %s, remove-group %s"
     (count servers) (:group-name group) (:remove-group group))
    (if (:remove-group group)
      (compute/destroy-nodes-in-group compute (name (:group-name group)))
      (doseq [node nodes] (compute/destroy-node compute node)))
    (->
     session
     (assoc-in [:groups-old-nodes (:group-name group)] nodes)
     (update-in [:old-nodes] concat nodes))))

;;; deltas-fn's, for calculating node counts to add or remove
(defn- deltas-for-converge-to-count
  "Find the difference between the required and actual node counts by group."
  [group]
  (assoc group :delta-count (- (:count group) (count (:servers group)))))

(defn- deltas-for-delta-count
  "Find the difference between the required and actual node counts by group."
  [group]
  (assoc group :delta-count (:count group)))

(defn- adjust-node-count
  "Adjust the server count by :delta-count nodes."
  [session op group]
  (let [session (->
                 session
                 (assoc :group group)
                 (environment/session-with-environment
                   (environment/merge-environments
                    (:environment session)
                    (:environment group))))]
    (logging/infof "adjust-server-count %s" (:group-name group))
    (op session)))

(defn- server-with-packager
  "Add the target packager to the session"
  [server]
  (update-in server [:packager]
             (fn [p] (or p
                         (-> server :image :packager)
                         (compute/packager (:image server))))))


(defn server
  "Take a `group` and a `node`, an `options` map and combine them to produce
   a server.

   The group os-family, os-version, are replaced with the details form the
   node. The :node key is set to `node`, and the :node-id and :packager keys
   are set.

   `options` allows adding extra keys on the server."
  [group node options]
  (->
   group
   (update-in [:image :os-family] (fn [f] (or (node/os-family node) f)))
   (update-in [:image :os-version] (fn [f] (or (node/os-version node) f)))
   (update-in [:node-id] (fn [id] (or (keyword (str (node/id node))) id)))
   (assoc :node node)
   server-with-packager
   (merge options)))

(defn update-group-servers
  [groups old-nodes new-nodes]
  (map
   (fn [{:keys [group-name] :as group}]
     (->
      group
      (update-in [:servers] (fn [servers]
                              (remove
                               (comp (set (get old-nodes group-name)) :node)
                               servers)))
      (update-in [:servers] concat
                 (map #(server group % nil) (get new-nodes group-name)))))
   groups))

(defn reduce-adjust-nodes
  "reduce the result of an adjust-node onto a session"
  [session session-adjust]
  (->
   session
   (update-in [:new-nodes] concat (:new-nodes session-adjust))
   (update-in [:old-nodes] concat (:old-nodes session-adjust))
   (update-in [:groups-old-nodes] merge (:groups-old-nodes session-adjust))
   (update-in [:groups-new-nodes] merge (:groups-new-nodes session-adjust))))

(defn adjust-session-for-nodes
  "Take :new-nodes and :old-nodes, and adjust :original-nodes and :all-nodes"
  [session]
  (->
   session
   (assoc :original-nodes (:all-nodes session))
   (update-in [:all-nodes]
              #(vec (->>
                     %
                     (concat (:new-nodes session))
                     (remove
                      (fn [node] (some
                                  (fn [n] (identical? n node))
                                  (:old-nodes session)))))))
   (update-in [:groups]
              update-group-servers
              (:groups-old-nodes session)
              (:groups-new-nodes session))))

(defn serial-adjust-node-counts
  "Start or stop the specified number of nodes."
  [session op groups]
  (logging/tracef "serial-adjust-node-counts")
  (adjust-session-for-nodes
   (reduce
    (fn [session group] (adjust-node-count session op group))
    session
    groups)))

(defn parallel-adjust-node-counts
  "Start or stop the specified number of nodes."
  [session op groups]
  (logging/tracef "parallel-adjust-node-counts")
  (->>
   groups
   (map
    (fn p-a-n-c-future [group]
      (future (adjust-node-count session op group))))
   futures/add
   doall ;; force generation of all futures
   (map
    (fn p-a-n-c-deref [f]
      (futures/deref-with-logging f "Adjust node count")))
   (reduce reduce-adjust-nodes session)
   adjust-session-for-nodes))

(defn lift-with-alternative-groups-and-phases
  [session f]
  (assoc (f session)
    :groups (:groups session)
    :phase-list (:phase-list session)))

(defn lift-destroy-server
  [session]
  (lift-with-alternative-groups-and-phases
    session
    (fn [session]
      (lift-nodes
       (-> session
           (update-in [:groups]
                      #(->>
                        (filter :servers-to-remove %)
                        (map (fn [group]
                               (assoc group
                                 :servers (:servers-to-remove group))))))
           (assoc :phase-list [:destroy-server]))))))

(defn destroy-servers
  [session]
  ((environment/get-for session [:algorithms :converge-fn])
   session
   remove-nodes
   (filter :servers-to-remove (:groups session))))

(defn lift-destroy-group
  [session]
  (lift-with-alternative-groups-and-phases
    session
    (fn [session]
      (lift-group-phase
       (-> session
           (update-in [:groups]
                      #(->> %
                            (filter (comp neg? (fn [g] (:delta-count g 0))))
                            (filter (comp empty? :servers))))
           (assoc :phase-list [:destroy-group]))))))

(defn lift-create-group
  [session]
  (lift-with-alternative-groups-and-phases
    session
    (fn [session]
      (lift-group-phase
       (-> session
           (update-in [:groups]
                      #(->> %
                            (filter (comp pos? (fn [g] (:delta-count g 0))))
                            (filter (comp empty? :servers))))
           (assoc :phase-list [:create-group]))))))

(defn create-servers
  [session]
  ((environment/get-for session [:algorithms :converge-fn])
   session
   create-nodes
   (filter (comp pos? :delta-count) (:groups session))))

(defn- adjust-server-counts
  "Adjust the server counts, given a compute facility and a map of ops and
   number of instances. Returns a session object with :original-nodes
   :all-nodes, :new-nodes and :old-nodes keys.

   - for nodes to be removed, select the nodes to be removed.
   - for nodes to be removed, run :destroy-server phase on each.
   - destroy any nodes required
   - for groups with no nodes left, run :destroy-group on each.
   - for groups with no nodes, that should have nodes started, run :create-group
   - create any nodes required"
  [session]
  (-> session
      (update-in [:groups] (partial map servers-to-remove))
      lift-destroy-server
      destroy-servers
      lift-destroy-group
      lift-create-group
      create-servers))

(def
  ^{:doc
    "Flag to control output of warnings about undefined phases in calls to lift
     and converge."
    :dynamic true}
  *warn-on-undefined-phase* true)

(defn- warn-on-undefined-phase
  "Generate a warning for the elements of the session's :phase-list that are not
   defined in the session's :groups.
   No warnings are generated for the settings or configure phases."
  [session]
  (when *warn-on-undefined-phase*
    (when-let [undefined (seq
                          (set/difference
                           (set (filter keyword? (:phase-list session)))
                           #{:settings :configure}
                           (set
                            (concat
                             (->>
                              (:groups session)
                              (map (comp keys :phases))
                              (reduce concat))
                             (keys (:inline-phases session))))))]
      (logging/warnf
       "Undefined phases: %s"
       (string/join ", " (map name undefined)))))
  session)

(defn- group-with-prefix
  [prefix node-spec]
  (update-in node-spec [:group-name]
             (fn [group-name] (keyword (str prefix (name group-name))))))

(defn- node-map-with-prefix [prefix node-map]
  (zipmap
   (map #(group-with-prefix prefix %) (keys node-map))
   (vals node-map)))

(defn- phase-list-with-configure
  "Ensure that the `phase-list` contains the :configure phase, prepending it if
  not."
  [phase-list]
  (->
   phase-list
   (ensure-phase :configure)
   (ensure-phase :settings)))

(defn- phase-list-with-default
  "Add the default configure phase if the `phase-list` is empty"
  [phase-list]
  (if (seq phase-list) phase-list [:settings :configure]))

(defn- session-with-configure-phase
  "Add the configure phase to the session's :phase-list if not present."
  [session]
  (update-in session [:phase-list] phase-list-with-configure))

(defn- session-with-default-phase
  "Add the default phase to the session's :phase-list if none supplied."
  [session]
  (update-in session [:phase-list]
             (fn [phase-list]
               (-> phase-list
                   phase-list-with-default
                   (ensure-phase :settings)))))

(defn- node-in-types?
  "Predicate for matching a node belonging to a set of node types"
  [node-types node]
  (some #(= (node/group-name node) (name (% :group-name))) node-types))

(defn- nodes-for-group
  "Return the nodes that have a group-name that matches one of the node types"
  [nodes group]
  (let [group-name (name (:group-name group))]
    (filter #(node/node-in-group? group-name %) nodes)))

(defn- group-spec?
  "Predicate for testing if argument is a node-spec.
   This is not exhaustive, and not intended for general use."
  [x]
  (and (map? x) (:group-name x) (keyword? (:group-name x))))

(defn nodes-in-set
  "Build a map of node-spec to nodes for the given `node-set`.
   A node set can be a node spec, a map from node-spec to a sequence of nodes,
   or a sequence of these.

   The prefix is applied to the group-name of each node-spec in the result.
   This allows you to build seperate clusters based on the same node-spec's.

   The return value is a map of node-spec to node sequence.

   Example node sets:
       node-spec-1
       [node-spec1 node-spec-2]
       {node-spec #{node1 node2}}
       [node-spec1 node-spec-2 {node-spec #{node1 node2}}]"
  [node-set prefix nodes]
  (letfn [(ensure-set [x] (if (set? x) x #{x}))
          (ensure-set-values
           [m]
           (zipmap (keys m) (map ensure-set (vals m))))]
    (cond
     (and (map? node-set) (not (group-spec? node-set)))
     (ensure-set-values (node-map-with-prefix prefix node-set))
     (group-spec? node-set)
     (let [group (group-with-prefix prefix node-set)]
       {group (set (nodes-for-group nodes group))})
     :else (reduce
            #(merge-with concat %1 %2) {}
            (map #(nodes-in-set % prefix nodes) node-set)))))


(defn groups-with-servers
  "Takes a map from node-spec to sequence of nodes, and converts it to a
   sequence of group definitions, containing a server for each node in then
   :servers key of each group.  The server will contain the node-spec,
   updated with any information that was available from the node.

       (groups-with-servers {(node-spec \"spec\" {}) [a b c]})
         => [{:group-name \"spec\"
              :servers [{:group-name \"spec\" :node a}
                        {:group-name \"spec\" :node b}
                        {:group-name \"spec\" :node c}]}]

   `options` allows adding extra keys to the servers."
  [node-map execute-node?]
  (for [[group nodes] node-map]
    (assoc group
      :servers (map
                (fn [node]
                  (server group node {:invoke-only (not (execute-node? node))}))
                (filter node/running? nodes)))))

(defn session-with-all-nodes
  "If the :all-nodes key is not set, then the nodes are retrieved from the
   compute service if possible."
  [session]
  (let [nodes (filter
               node/running?
               (or (:all-nodes session) ; empty list is ok
                   (if-let [compute (environment/get-for
                                     session [:compute] nil)]
                     (do
                       (logging/info "retrieving nodes")
                       (compute/nodes compute))
                     (->>
                      (:node-set session)
                      (filter #(and (map? %) (every? map? (keys %))))
                      (mapcat vals)
                      (mapcat #(if (seq? %) % [%]))
                      (filter node/node?)
                      (distinct)))))]
    (assoc session :all-nodes nodes :selected-nodes nodes)))

(defn session-with-groups
  "Takes the :selected-nodes, :all-nodes. :node-set and :prefix keys and compute
   the groups for the session, updating the :selected-nodes, :all-nodes
   and :groups keys of the session.

   The :groups key is set to a sequence of groups, each containing its
   list of servers on the :servers key."
  [session]
  (let [nodes (:selected-nodes session)
        all-nodes (:all-nodes session)
        all-targets (nodes-in-set
                     (:node-set session) (:prefix session) all-nodes)
        targets (nodes-in-set (:node-set session) (:prefix session) nodes)
        nodes (or (or (seq nodes)
                      (filter
                       node/running?
                       (reduce concat (vals targets)))))
        plan-targets (when-let [all-node-set (seq (:all-node-set session))]
                       (-> (nodes-in-set all-node-set nil all-nodes)
                           (utils/dissoc-keys (keys targets))))]
    (->
     session
     (assoc :all-nodes (or (seq all-nodes)
                           (filter
                            node/running?
                            (reduce
                             concat
                             (concat
                              (vals all-targets) (vals plan-targets))))))
     (assoc :selected-nodes nodes)
     (assoc :groups (concat
                     (groups-with-servers targets (set nodes))
                     (groups-with-servers plan-targets (constantly false)))))))

(defn all-node-set-selector
  "Select all nodes for groups in the node-set for processing"
  [session]
  (assoc session :selected-nodes (:all-nodes session)))

(defn new-node-set-selector
  "Select all new nodes for groups in the node-set for processing"
  [session]
  (assoc session :selected-nodes (:new-nodes session)))

(defn select-node-set
  "Select a node-set of nodes to be passed to lift"
  [session]
  ((:node-set-selector session all-node-set-selector) session))

(defn lift*
  "Lift the nodes specified in the session :node-set key.
   - :node-set     - a specification of nodes to lift
   - :all-nodes    - a sequence of all known nodes
   - :all-node-set - a specification of nodes to invoke (but not lift)"
  [session]
  (logging/debugf "pallet version: %s" (version))
  (logging/tracef "lift* phases %s" (vec (:phase-list session)))
  (->
   session
   session-with-all-nodes
   select-node-set
   session-with-groups
   session-with-default-phase
   warn-on-undefined-phase
   lift-nodes))


(defn converge*
  "Converge the node counts of each node-spec in `:node-set`, executing each of
   the configuration phases on all the group-names in `:node-set`. The
   phase-functions are also executed, but not applied, for any other nodes in
   `:all-node-set`"
  [session]
  {:pre [(:node-set session)]}
  (logging/debugf "pallet version: %s" (version))
  (logging/tracef "converge* phases %s" (vec (:phase-list session)))
  (logging/tracef "converge* node-set %s" (vec (:node-set session)))
  (->
   session
   session-with-all-nodes
   session-with-groups
   session-with-configure-phase
   (update-in [:groups] (partial map deltas-for-converge-to-count))
   adjust-server-counts
   lift*))

(defmacro or-fn [& args]
  `(fn or-args [current#]
     (or current# ~@args)))

(defn- compute-from-options
  [current-value {:keys [compute compute-service]}]
  (or current-value
      compute
      (and compute-service
           (compute/compute-service
            (:provider compute-service)
            :identity (:identity compute-service)
            :credential (:credential compute-service)
            :extensions (:extensions compute-service)
            :node-list (:node-list compute-service)))))

(defn- blobstore-from-options
  [current-value {:keys [blobstore blobstore-service]}]
  (or current-value
      blobstore
      (and blobstore-service
           (blobstore/service
            (:provider blobstore-service)
            :identity (:identity blobstore-service)
            :credential (:credential blobstore-service)
            :extensions (:extensions blobstore-service)))))

(def
  ^{:doc "Algorithms to use when none specified"}
  default-algorithms
  {:lift-fn parallel-lift
   :converge-fn parallel-adjust-node-counts
   :execute-status-fn stop-execution-on-error})

(defn default-environment
  "Specify the built-in default environment"
  []
  {:blobstore nil
   :compute nil
   :user utils/*admin-user*
   :middleware *middleware*
   :algorithms default-algorithms})

(defn- effective-environment
  "Build the effective environment for the session map.
   This merges the explicitly passed :environment, with that
   defined on the :compute service."
  [session]
  (assoc
   session
   :environment
   (environment/merge-environments
    (default-environment)                                     ; global default
    (utils/find-var-with-require 'pallet.config 'environment) ; project default
    (-?> session :environment :compute environment/environment) ;service default
    (:environment session))))                                 ; session default

(def ^{:doc "args that are really part of the environment"}
  environment-args [:compute :blobstore :user :middleware :provider-options])

(defn session-with-environment
  "Build a session map from the given options, combining the service specific
   options with those given in the converge or lift invocation."
  [{:as options}]
  (->
   options
   (update-in                           ; ensure backwards compatable
    [:environment]
    merge (select-keys options environment-args))
   (assoc :executor default-executors)
   (utils/dissoc-keys environment-args)
   (effective-environment)))

(def ^{:doc "A set of recognised argument keywords, used for input checking."
       :private true}
  argument-keywords
  #{:compute :blobstore :phase :user :prefix :middleware :all-node-set
    :all-nodes :parameters :environment :node-set :phase-list
    :node-set-selector :provider-options})

(defn- check-arguments-map
  "Check an arguments map for errors."
  [{:as options}]
  (let [unknown (remove argument-keywords (keys options))]
    (when (and (:phases options) (not (:phase options)))
      (throw+
       {:type :invalid-argument
        :message (str
                  "Please pass :phase and not :phases. :phase takes a single "
                  "phase or a sequence of phases.")
        :invalid-keys unknown}))
    (when (seq unknown)
      (throw+
       {:type :invalid-argument
        :message (format "Invalid argument keywords %s" (vec unknown))
        :invalid-keys unknown})))
  options)

(defn- identify-anonymous-phases
  "For all inline phase defintions in the session's :phase-list,
   generate a keyword for the phase, adding an entry to the session's
   :inline-phases map containing the phase definition, and replacing the
   phase defintion in the :phase-list with the keyword."
  [session]
  (reduce
   (fn [session phase]
     (if (keyword? phase)
       (update-in session [:phase-list] #(conj (or % []) phase))
       (let [phase-kw (keyword (name (gensym "phase")))]
         (->
          session
          (assoc-in [:inline-phases phase-kw] phase)
          (update-in [:phase-list] conj phase-kw)))))
   (dissoc session :phase-list)
   (:phase-list session)))

(defn- group-spec-with-count
  "Take the given group-spec, and set the :count key to the value specified
   by `count`"
  [[group-spec count]]
  (assoc group-spec :count count))

(defn- node-set-for-converge
  "Takes the input, and translates it into a sequence of group-spec's.
   The input can be a single group-spec, a map from group-spec to node count,
   or a sequence of group-spec's"
  [group-spec->count]
  (cond
   ;; a single group-spec
   (and
    (map? group-spec->count)
    (:group-name group-spec->count)) [group-spec->count]
   ;; a map from group-spec to count
   (map? group-spec->count) (map group-spec-with-count group-spec->count)
   :else group-spec->count))

(defn converge
  "Converge the existing compute resources with the counts specified in
   `group-spec->count`. New nodes are started, or nodes are destroyed,
   to obtain the specified node counts.

   `group-spec->count` can be a map from group-spec to node count, or can be a
   sequence of group-specs containing a :count key.

   The compute service may be supplied as an option, otherwise the bound
   compute-service is used.


   This applies the bootstrap phase to all new nodes and the configure phase to
   all running nodes whose group-name matches a key in the node map.  Additional
   phases can also be specified in the options, and will be applied to all
   matching nodes.  The :configure phase is always applied, by default as the
   first (post bootstrap) phase.  You can change the order in which
   the :configure phase is applied by explicitly listing it.

   An optional group-name prefix may be specified. This will be used to modify
   the group-name for each group-spec, allowing you to build multiple discrete
   clusters from a single set of group-specs."
  [group-spec->count & {:keys [compute blobstore user phase prefix middleware
                               all-nodes all-node-set environment]
                        :as options}]
  (converge*
   (->
    options
    (assoc :node-set (expand-group-spec-with-counts group-spec->count)
           :phase-list (if (sequential? phase)
                         phase
                         (if phase [phase] [:configure])))
    check-arguments-map
    session-with-environment
    identify-anonymous-phases)))

(defn lift
  "Lift the running nodes in the specified node-set by applying the specified
   phases.  The compute service may be supplied as an option, otherwise the
   bound compute-service is used.  The configure phase is applied by default
   unless other phases are specified.

   node-set can be a node type, a sequence of node types, or a map
   of node type to nodes. Examples:
              [node-type1 node-type2 {node-type #{node1 node2}}]
              node-type
              {node-type #{node1 node2}}

   options can also be keywords specifying the phases to apply, or an immediate
   phase specified with the phase macro, or a function that will be called with
   each matching node.

   Options:
    :compute         a jclouds compute service
    :compute-service a map of :provider, :identity, :credential, and
                     optionally :extensions for constructing a jclouds compute
                     service.
    :phase           a phase keyword, phase function, or sequence of these
    :middleware      the middleware to apply to the configuration pipeline
    :prefix          a prefix for the group-name names
    :user            the admin-user on the nodes"
  [node-set & {:keys [compute phase prefix middleware all-node-set environment]
               :as options}]
  (lift*
   (->
    options
    (assoc :node-set (expand-cluster-groups node-set)
           :phase-list (if (sequential? phase)
                         phase
                         (if phase [phase] [:configure])))
    check-arguments-map
    (dissoc :phase)
    session-with-environment
    identify-anonymous-phases)))



;;; Cluster operations
(defn cluster-groups
  "Return the groups in the passed cluster or sequence of clusters."
  [cluster]
  (if (seq? cluster)
    (mapcat :groups cluster)
    (:groups cluster)))

(defn converge-cluster
  "Converge the specified cluster. As for `converge`, but takes a cluster-spec
   or sequence of cluster-specs."
  [cluster & options]
  (apply converge (cluster-groups cluster) options))

(defn lift-cluster
  "Lift the specified cluster.  As for `lift`, but takes a cluster-spec
   or sequence of cluster-specs."
  [cluster & options]
  (apply lift (cluster-groups cluster) options))

(defn destroy-cluster
  "Destroy the specified cluster. As for `converge`, but takes a cluster-spec
   or sequence of cluster-specs."
  [cluster & options]
  (apply converge (map #(assoc % :count 0) (cluster-groups cluster)) options))
