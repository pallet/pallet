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
                    configuration (via pallet.compute.Node/group-name)
- group          :: A group of identically configured nodes, represented as a
                    group-spec, together with the servers that are running
                    for that group-spec.
- group name     :: The name used to identify a group.
- server         :: A map used to descibe the node, image, etc of a single
                    node running as part of a group. A server has the
                    following keys :group-name, :node, :node-id and server-spec
                    keys.
- phase list     :: A list of phases to be used
- action plan    :: A list of resources that should be run."
  {:author "Hugo Duncan"}
  (:require
   [pallet.blobstore :as blobstore]
   [pallet.compute :as compute]
   [pallet.environment :as environment]
   [pallet.execute :as execute]
   [pallet.utils :as utils]
   [pallet.script :as script]
   [pallet.target :as target]
   [pallet.parameter :as parameter]
   [pallet.resource :as resource]
   [pallet.thread-expr :as thread-expr]
   [clojure.contrib.condition :as condition]
   [clojure.contrib.logging :as logging]
   [clojure.contrib.map-utils :as map-utils]
   [clojure.set :as set]
   [clojure.string :as string])
  (:use
   [clojure.contrib.core :only [-?>]]))

(defn version
  "Returns the pallet version."
  []
  (or
   (System/getProperty "pallet.version")
   (if-let [version (utils/slurp-resource "pallet-version")]
     (string/trim version))))

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
   :network   a map for network connectivity options
              inbound-ports"
  [& {:keys [image hardware location network] :as options}]
  {:pre [(or (nil? image) (map? image))]}
  options)

(defn- merge-specs
  "Merge specs, using comp for :phases"
  [a b]
  (let [phases (merge-with #(comp %2 %1) (:phases a) (:phases b))]
    (->
     (merge a b)
     (thread-expr/when-not->
      (empty? phases)
      (assoc :phases phases)))))

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
   - :packager       override the choice of packager to use
   - :node-spec      default node-spec for this server-spec
   - :server-spec    is a server-spec, or sequence thereof, and is used to
                     inherit phases, etc."
  [& {:keys [phases packager node-spec extends image hardware location network]
      :as options}]
  (->
   node-spec
   (merge options)
   (extend-specs extends)
   (dissoc :extends :node-spec)))

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
   & {:keys [extends count image phases packager node-spec] :as options}]
  {:pre [(or (nil? image) (map? image))]}
  (->
   node-spec
   (merge options)
   (extend-specs extends)
   (dissoc :extends :node-spec)
   (assoc :group-name (keyword name))))

(defn make-node
  "Create a node definition.  See defnode."
  [name image & {:as phase-map}]
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
   :deprecated "0.4.6"}
  [group-name & options]
  (let [[group-name options] (name-with-attributes group-name options)]
    `(def ~group-name (make-node '~(name group-name) ~@options))))


(defn- add-request-keys-for-0-4-5-compatibility
  "Add target keys for compatibility.
   This function adds back deprecated keys"
  [handler]
  (fn [request]
    (handler
     (-> request
         (assoc :node-type (:group request))
         (assoc :target-packager (-> request :server :packager))
         (assoc :target-id (-> request :server :node-id))
         (assoc :target-node (-> request :server :node))))))

(defn show-target-keys
  "Middleware that is useful in debugging."
  [handler]
  (fn [request]
    (logging/info
     (format
      "TARGET KEYS :phase %s :node-id %s :group-name %s :packager %s"
      (:phase request)
      (-> request :server :node-id)
      (-> request :server :group-name)
      (-> request :server :packager)))
    (handler request)))

(defn- resource-invocations [request]
  {:pre [(:phase request)]}
  (if-let [f (some
              (:phase request)
              [(:phases (:server request)) (:inline-phases request)])]
    (script/with-template (resource/script-template request)
      (f request))
    request))

(defn- bootstrap-script
  "Generates the bootstrap script for the :group specified in the request.

   This builds an action plan for the bootstrap phase, and then realises the
   plan, verifying that only script generating resources are included in the
   bootstrap.  This limitation is necessary so that the script can be executed
   over a \"fire and forget\" transport."
  [request]
  {:pre [(get-in request [:group :image :os-family])
         (get-in request [:group :packager])]}
  (let [request (assoc request
                  :phase :bootstrap
                  :server (assoc (:group request) :node-id :bootstrap-id))
        cmds (resource/produce-phase (resource-invocations request))]
    (if-not (and (every? #(= :remote (:location %)) cmds) (>= 1 (count cmds)))
      (condition/raise
       :type :booststrap-contains-local-resources
       :message (format
                 "Bootstrap can not contain local resources %s"
                 (pr-str cmds))))
    (if-let [f (:f (first cmds))]
      (script/with-template
        (resource/script-template-for-node-spec (:group request))
        (logging/info
         (format
          "Bootstrap script for - :os-family %s, :packager %s, *template* %s"
          (-> request :group :image :os-family)
          (-> request :group :packager)
          (vec script/*script-context*)))
        (:cmds (f request)))
      "")))

(defn- create-nodes
  "Create count nodes based on the template for the group. The boostrap argument
expects a map with :authorize-public-key and :bootstrap-script keys.  The
bootstrap-script value is expected tobe a function that produces a script that
is run with root privileges immediatly after first boot."
  [group count request]
  {:pre [(map? group)]}
  (logging/info
   (str "Starting " count " nodes for " (:group-name group)
        " os-family " (-> group :image :os-family)))
  (let [compute (:compute request)
        request (update-in request [:group]
                           #(compute/ensure-os-family compute %))
        request (assoc-in request [:group :packager]
                          (compute/packager (-> request :group :image)))
        init-script (bootstrap-script request)]
    (logging/trace
     (format "Bootstrap script:\n%s" init-script))
    (concat
     (map :node (:servers group))
     (compute/run-nodes compute group count (:user request) init-script))))

(defn- destroy-nodes
  "Destroys the specified number of nodes with the given group.  Nodes are
   selected at random."
  [group destroy-count request]
  (logging/info
   (str "destroying " destroy-count " nodes for " (:group-name group)))
  (let [compute (:compute request)
        servers (:servers group)]
    (if (= destroy-count (count servers))
      (do
        (compute/destroy-nodes-in-group compute (name (:group-name group)))
        nil)
      (let [nodes (map :node servers)]
        (doseq [node (take destroy-count nodes)]
          (compute/destroy-node compute node))
        (drop destroy-count nodes)))))

(defn- node-count-difference
  "Find the difference between the required and actual node counts by group."
  [groups]
  (->>
   groups
   (map
    (fn [group]
      (vector (:group-name group) (- (:count group) (count (:servers group))))))
   (into {})))

(defn- adjust-node-count
  "Adjust the node by delta nodes"
  [{:keys [group-name environment servers] :as group} delta request]
  (let [request (environment/request-with-environment
                  (assoc request :group group)
                  (environment/merge-environments
                   (:environment request) environment))]
    (logging/info (format "adjust-node-count %s %d" group-name delta))
    (cond
     (pos? delta) (create-nodes group delta request)
     (neg? delta) (destroy-nodes group (- delta) request)
     :else (map :node servers))))

(defn serial-adjust-node-counts
  "Start or stop the specified number of nodes."
  [delta-map request]
  (logging/trace (str "serial-adjust-node-counts" delta-map))
  (reduce
   concat
   (doall
    (map
     (fn [group]
       (adjust-node-count group ((:group-name group) delta-map 0) request))
     (:groups request)))))

(defn parallel-adjust-node-counts
  "Start or stop the specified number of nodes."
  [delta-map request]
  (logging/trace (str "parallel-adjust-node-counts" delta-map))
  (mapcat
   deref
   (doall
    (map
     (fn [group]
       (future (adjust-node-count group ((:group-name group) delta-map 0) request)))
     (:groups request)))))

(defn- converge-node-counts
  "Converge the nodes counts, given a compute facility and a reference number of
   instances."
  [request]
  (logging/info "converging nodes")
  (assoc request
    :all-nodes ((environment/get-for request [:algorithms :converge-fn])
                (node-count-difference (:groups request))
                request)))

(defn execute-with-ssh
  [request]
  (let [target-node (-> request :server :node)]
    (execute/ssh-cmds
     (assoc request
       :address (compute/node-address target-node)
       :ssh-port (compute/ssh-port target-node)))))

(defn execute-with-local-sh
  "Middleware to execute on localhost with shell"
  [handler]
  (fn [{:as request}]
    (execute/local-sh-cmds request)))

(defn parameter-keys [node node-type]
  [:default
   (compute/packager (node-type :image))
   (target/os-family (node-type :image))])

(defn with-target-parameters
  "Middleware to set parameters based on a specified parameter map"
  [handler parameters]
  (fn [request]
    (handler
     (assoc
         request :parameters
         (parameter/from-map
          parameters
          (parameter-keys
           (-> request :server :node) (:server request)))))))

(defn- build-commands
  "Middlware that generates the commands that should be run for the execution
   plan.
   If no commands are generated for the current phase, then the pipleline short
   circuits and returns here."
  [handler]
  (fn [request]
    {:pre [handler
           (-> request :server :image :os-family)
           (-> request :server :packager)]}
    (if-let [commands (script/with-template (resource/script-template request)
                        (resource/produce-phase request))]
      (handler (assoc request :commands commands))
      [nil request])))

(defn- apply-phase-to-node
  "Apply a phase to a node"
  [request]
  {:pre [(:server request)]}
  (let [request (environment/request-with-environment
                  request
                  (environment/merge-environments
                   (:environment request)
                   (-> request :server :environment)))
        middleware (:middleware request)]
    ((utils/pipe
      add-request-keys-for-0-4-5-compatibility
      build-commands
      middleware
      execute-with-ssh)
     request)))

(defn wrap-no-exec
  "Middleware to report on the request, without executing"
  [_]
  (fn [request]
    (logging/info
     (format
      "Commands on node %s as user %s"
      (pr-str (:node request))
      (pr-str (:user request))))
    (letfn [(execute
             [cmd]
             (logging/info (format "Commands %s" cmd))
             cmd)]
      (resource/execute-commands request {:script/bash execute
                                          :transfer/to-local (fn [& _])
                                          :transfer/from-local (fn [& _])}))))

(defn wrap-local-exec
  "Middleware to execute only local functions"
  [_]
  (fn [request]
    (letfn [(execute [cmd] nil)]
      (resource/execute-commands request {:script/bash execute
                                          :transfer/to-local (fn [& _])
                                          :transfer/from-local (fn [& _])}))))

(defn wrap-with-user-credentials
  "Middleware to user the request :user credentials for SSH authentication."
  [handler]
  (fn [request]
    (let [user (:user request)]
      (logging/info (format "Using identity at %s" (:private-key-path user)))
      (execute/possibly-add-identity
       (execute/default-agent) (:private-key-path user) (:passphrase user)))
    (handler request)))

(def *middleware* wrap-with-user-credentials)

(defmacro with-middleware
  "Wrap node execution in the given middleware. A middleware is a function of
   one argument (a handler function, that is the next middleware to call) and
   returns a dunction of one argument (the request map).  Middleware can be
   composed with the pipe macro."
  [f & body]
  `(binding [*middleware* ~f]
     ~@body))

(defn- reduce-phase-results
  "Combine the execution results."
  [request results]
  (reduce
   (fn apply-phase-accumulate [request [result req :as arg]]
     (let [target-id (-> req :server :node-id)
           param-keys [:parameters :host target-id]]
       (->
        request
        (assoc-in [:results target-id (:phase req)] result)
        (update-in
         param-keys
         (fn [p]
           (map-utils/deep-merge-with
            (fn [x y] (or y x)) p (get-in req param-keys)))))))
   request
   results))

(defn- reduce-results
  "Reduce across all phase results"
  [request results]
  (reduce
   (fn lift-nodes-reduce-result [request req]
     (let [req (reduce-phase-results request req)]
       (->
        request
        (update-in
         [:results]
         #(map-utils/deep-merge-with
           (fn [x y] (or y x)) (or % {}) (:results req)))
        (update-in
         [:parameters]
         #(map-utils/deep-merge-with
           (fn [x y] (or y x)) % (:parameters req))))))
   request
   results))

(defn- plan-for-server
  "Build an action plan for the specified server."
  [request server]
  {:pre [(:node server) (:node-id server)]}
  (resource-invocations
   (->
    request
    (assoc :server server)
    (environment/request-with-environment
      (environment/merge-environments
       (:environment request)
       (-> request :server :environment))))))

(defn- plan-for-servers
  "Build an action plan for the specified servers."
  [request servers]
  (reduce plan-for-server request servers))

(defn- plan-for-groups
  "Build an invocation map for specified node-type map."
  [request groups]
  (reduce
   (fn [request group]
     (plan-for-servers (assoc request :group group) (:servers group)))
   request groups))

(defn- plan-for-phases
  "Build an invocation map for specified phases and nodes.
   This allows configuration to be accumulated in the request parameters."
  [request]
  (reduce
   (fn [request phase]
     (plan-for-groups (assoc request :phase phase) (:groups request)))
   request (:phase-list request)))

(defn sequential-apply-phase
  "Apply a phase to a sequence of nodes"
  [request servers]
  (logging/info
   (format
    "apply-phase %s for %s with %d nodes"
    (:phase request) (-> request :server :group-name) (count servers)))
  (for [server servers]
    (apply-phase-to-node
     (assoc request :server server))))

(defn parallel-apply-phase
  "Apply a phase to a sequence of nodes"
  [request servers]
  (logging/info
   (format
    "apply-phase %s for %s with %d nodes"
    (:phase request) (-> request :server :group-name) (count servers)))
  (map (fn [server]
         (future (apply-phase-to-node
                  (assoc request :server server))))
       servers))

(defn sequential-lift
  "Sequential apply the phases."
  [request]
  (for [phase (resource/phase-list (:phase-list request))
        group (:groups request)]
    (sequential-apply-phase
     (assoc request :phase phase :group group)
     (:servers group))))

(defn parallel-lift
  "Apply the phases in sequence, to nodes in parallel."
  [request]
  (for [phase (resource/phase-list (:phase-list request))]
    (mapcat
     #(map deref %) ; make sure all nodes complete before next phase
     (for [group (:groups request)]
       (parallel-apply-phase
        (assoc request :phase phase :group group)
        (:servers group))))))

(defn lift-nodes
  "Lift nodes in target-node-map for the specified phases."
  [request]
  (logging/trace (format "lift-nodes phases %s" (vec (:phase-list request))))
  (let [lift-fn (environment/get-for request [:algorithms :lift-fn])]
    (reduce-results
     request
     (->
      request
      plan-for-phases
      lift-fn))))

(def
  ^{:doc
    "Flag to control output of warnings about undefined phases in calls to lift
     and converge."}
  *warn-on-undefined-phase* true)

(defn- warn-on-undefined-phase
  "Generate a warning for the elements of the request's :phase-list that are not
   defined in the request's :groups."
  [request]
  (when *warn-on-undefined-phase*
    (when-let [undefined (seq
                          (set/difference
                           (set (filter keyword? (:phase-list request)))
                           (set
                            (concat
                             (->>
                              (:groups request)
                              (map (comp keys :phases))
                              (reduce concat))
                             (keys (:inline-phases request))))))]
      (logging/warn
       (format
        "Undefined phases: %s"
        (string/join ", " (map name undefined))))))
  request)

(defn- nodes-in-map
  "Return nodes with group-names corresponding to the keys in node-map"
  [node-map nodes]
  (let [group-names (->> node-map keys (map :group-name) (map name) set)]
    (->> nodes
         (filter compute/running?)
         (filter #(-> % compute/group-name group-names)))))

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
  (if (some #{:configure} phase-list)
    phase-list
    (concat [:configure] phase-list)))

(defn- phase-list-with-default
  "Add the default configure phase if the `phase-list` is empty"
  [phase-list]
  (if (seq phase-list) phase-list [:configure]))

(defn- request-with-configure-phase
  "Add the configure phase to the request's :phase-list if not present."
  [request]
  (update-in request [:phase-list] phase-list-with-configure))

(defn- request-with-default-phase
  "Add the default phase to the request's :phase-list if none supplied."
  [request]
  (update-in request [:phase-list] phase-list-with-default))

(defn- node-in-types?
  "Predicate for matching a node belonging to a set of node types"
  [node-types node]
  (some #(= (compute/group-name node) (name (% :group-name))) node-types))

(defn- nodes-for-group
  "Return the nodes that have a group-name that matches one of the node types"
  [nodes group]
  (let [group-name (name (:group-name group))]
    (filter #(compute/node-in-group? group-name %) nodes)))

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

(defn- server-with-packager
  "Add the target packager to the request"
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
   (update-in [:image :os-family] (fn [f] (or (compute/os-family node) f)))
   (update-in [:image :os-version] (fn [f] (or (compute/os-version node) f)))
   (update-in [:node-id] (fn [id] (or (keyword (compute/id node)) id)))
   (assoc :node node)
   server-with-packager
   (merge options)))

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
  [node-map & {:as options}]
  (for [[group nodes] node-map]
    (assoc group
      :servers (map #(server group % options)
                    (filter compute/running? nodes)))))

(defn request-with-groups
  "Takes the :all-nodes, :node-set and :prefix keys and compute the groups
   for the request, updating the :all-nodes and :groups keys of the request.

   If the :all-nodes key is not set, then the nodes are retrieved from the
   compute service if possible, or are inferred from the :node-set value.

   The :groups key is set to a sequence of groups, each containing its
   list of servers on the :servers key."
  [request]
  (let [all-nodes (filter
                   compute/running?
                   (or (seq (:all-nodes request))
                       (when-let [compute (environment/get-for
                                           request [:compute] nil)]
                         (logging/info "retrieving nodes")
                         (compute/nodes compute))))
        targets (nodes-in-set (:node-set request) (:prefix request) all-nodes)
        plan-targets (if-let [all-node-set (:all-node-set request)]
                       (-> (nodes-in-set all-node-set nil all-nodes)
                           (utils/dissoc-keys (keys targets))))]
    (->
     request
     (assoc :all-nodes (or (seq all-nodes)
                           (filter
                            compute/running?
                            (reduce
                             concat
                             (concat (vals targets) (vals plan-targets))))))
     (assoc :groups (concat
                     (groups-with-servers targets)
                     (groups-with-servers plan-targets :invoke-only true))))))

(defn lift*
  "Lift the nodes specified in the request :node-set key.
   - :node-set     - a specification of nodes to lift
   - :all-nodes    - a sequence of all known nodes
   - :all-node-set - a specification of nodes to invoke (but not lift)"
  [request]
  (logging/trace (format "lift* phases %s" (vec (:phase-list request))))
  (->
   request
   request-with-groups
   request-with-default-phase
   warn-on-undefined-phase
   lift-nodes))

(defn converge*
  "Converge the node counts of each node-spec in `:node-set`, executing each of
   the configuration phases on all the group-names in `:node-set`. The
   phase-functions are also executed, but not applied, for any other nodes in
   `:all-node-set`"
  [request]
  {:pre [(:node-set request)]}
  (logging/trace
   (format "converge* %s %s" (:node-set request) (:phase-list request)))
  (->
   request
   request-with-groups
   converge-node-counts
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

(defn default-environment
  "Specify the built-in default environment"
  []
  {:blobstore nil
   :compute nil
   :user utils/*admin-user*
   :middleware *middleware*
   :algorithms {:lift-fn parallel-lift
                :converge-fn parallel-adjust-node-counts}})

(defn- effective-environment
  "Build the effective environment for the request map.
   This merges the explicitly passed :environment, with that
   defined on the :compute service."
  [request]
  (assoc
   request
   :environment
   (environment/merge-environments
    (default-environment)                                     ; global default
    (utils/find-var-with-require 'pallet.config 'environment) ; project default
    (-?> request :environment :compute environment/environment) ;service default
    (:environment request))))                                 ; request default

(def ^{:doc "args that are really part of the environment"}
  environment-args [:compute :blobstore :user :middleware])

(defn- request-with-environment
  "Build a request map from the given options, combining the service specific
   options with those given in the converge or lift invocation."
  [{:as options}]
  (->
   options
   (update-in                           ; ensure backwards compatable
    [:environment]
    merge (select-keys options environment-args))
   (utils/dissoc-keys environment-args)
   (effective-environment)))

(def ^{:doc "A set of recognised argument keywords, used for input checking."
       :private true}
  argument-keywords
  #{:compute :blobstore :phase :user :prefix :middleware :all-node-set
    :all-nodes :parameters :environment :node-set :phase-list})

(defn- check-arguments-map
  "Check an arguments map for errors."
  [{:as options}]
  (let [unknown (remove argument-keywords (keys options))]
    (when (and (:phases options) (not (:phase options)))
      (condition/raise
       :type :invalid-argument
       :message (str
                 "Please pass :phase and not :phases. :phase takes a single "
                 "phase or a sequence of phases.")
       :invalid-keys unknown))
    (when (seq unknown)
      (condition/raise
       :type :invalid-argument
       :message (format "Invalid argument keywords %s" (vec unknown))
       :invalid-keys unknown)))
  options)

(defn- identify-anonymous-phases
  "For all inline phase defintions in the request's :phase-list,
   generate a keyword for the phase, adding an entry to the request's
   :inline-phases map containing the phase definition, and replacing the
   phase defintion in the :phase-list with the keyword."
  [request]
  (reduce
   (fn [request phase]
     (if (keyword? phase)
       (update-in request [:phase-list] conj phase)
       (let [phase-kw (keyword (name (gensym "phase")))]
         (->
          request
          (assoc-in [:inline-phases phase-kw] phase)
          (update-in [:phase-list] conj phase-kw)))))
   (dissoc request :phase-list)
   (:phase-list request)))

(defn- node-spec-with-count
  "Take the given node-spec, and set the :count key to the value specified
   by `count`"
  [[node-spec count]]
  (assoc node-spec :count count))

(defn converge
  "Converge the existing compute resources with the counts specified in
   `node-spec->count`. New nodes are started, or nodes are destroyed,
   to obtain the specified node counts.

   `node-spec->count` can be a map from node-spec to node count, or can be a
   sequence of node-specs containing a :count key.

   The compute service may be supplied as an option, otherwise the bound
   compute-service is used.


   This applies the bootstrap phase to all new nodes and the configure phase to
   all running nodes whose group-name matches a key in the node map.  Additional
   phases can also be specified in the options, and will be applied to all
   matching nodes.  The :configure phase is always applied, by default as the
   first (post bootstrap) phase.  You can change the order in which
   the :configure phase is applied by explicitly listing it.

   An optional group-name prefix may be specified. This will be used to modify
   the group-name for each node-spec, allowing you to build multiple discrete
   clusters from a single set of node-specs."
  [node-spec->count & {:keys [compute blobstore user phase prefix middleware
                              all-nodes all-node-set environment]
                       :as options}]
  (converge*
   (->
    options
    (assoc :node-set (if (map? node-spec->count)
                       (map node-spec-with-count node-spec->count)
                       node-spec->count)
           :phase-list (if (sequential? phase) phase (if phase [phase] nil)))
    check-arguments-map
    request-with-environment
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
    (assoc :node-set node-set
           :phase-list (if (sequential? phase) phase (if phase [phase] nil)))
    check-arguments-map
    (dissoc :all-node-set :phase)
    request-with-environment
    identify-anonymous-phases)))
