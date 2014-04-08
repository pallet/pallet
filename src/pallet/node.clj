(ns pallet.node
  "Provider level API for nodes in pallet"
  (:refer-clojure :exclude [proxy])
  (:require
   [clojure.stacktrace :refer [print-cause-trace]]
   [taoensso.timbre :refer [debugf trace]]
   [pallet.compute.protocols :as impl]
   [pallet.user :refer [User]]
   [schema.core :as schema :refer [check required-key optional-key validate]]))

;;; TODO implement running?/terminated?/etc into a state flag
;;; TODO implement is-64bit? based on arch?

;;; # Node functions
(def Proxy
  {:port schema/Int})

(def NodeValues
  {(optional-key :compute-service) pallet.compute.protocols.ComputeService
   (optional-key :ssh-port) schema/Int
   (optional-key :primary-ip) String
   (optional-key :private-ip) String
   (optional-key :is-64bit) schema/Bool
   (optional-key :arch) String
   (optional-key :hostname) String
   (optional-key :run-state) (schema/enum
                              :running :stopped :suspended :terminated)
   (optional-key :proxy) Proxy
   :os-family schema/Keyword
   (optional-key :os-version) String
   :packager schema/Keyword
   (optional-key :image-user) User
   (optional-key :user) User
   (optional-key :hardware) {schema/Keyword schema/Any}})

(def Node
  "Schema for nodes"
  (assoc NodeValues :id String))

(defn validate-node
  "Predicate to test whether an object implements the Node protocol"
  [obj]
  (schema/validate Node obj))

(defn node?
  "Predicate to test whether an object implements the Node protocol"
  [obj]
  (not (schema/check Node obj)))

(defn ssh-port
  "Extract the port from the node's user Metadata"
  [node]
  {:pre [(validate Node node)]
   :post [(number? %)]}
  (:ssh-port node 22))

(defn primary-ip
  "Returns the first public IP for the node."
  [node]
  {:pre [(validate Node node)]}
  (:primary-ip node))

(defn private-ip
  "Returns the first private IP for the node."
  [node]
  {:pre [(validate Node node)]}
  (:private-ip node))

(defn is-64bit?
  "64 Bit OS predicate"
  [node]
  {:pre [(validate Node node)]}
  (:is-64bit node true))

(defn arch
  "Node architecture"
  [node]
  {:pre [(validate Node node)]}
  (:arch node))

(defn hostname
  "Return the node's hostname"
  [node]
  {:pre [(validate Node node)]}
  (:hostname node))

(defn os-family
  "Return a node's os-family, or nil if not available."
  [node]
  {:pre [(validate Node node)]}
  (:os-family node))

(defn os-version
  "Return a node's os-version, or nil if not available."
  [node]
  {:pre [(validate Node node)]}
  (:os-version node))

(defn running?
  "Predicate to test if node is running."
  [node]
  {:pre [(validate Node node)]}
  (= (:run-state node) :running))

(defn terminated?
  "Predicate to test if node is terminated."
  [node]
  {:pre [(validate Node node)]}
  (= (:run-state node) :terminated))

(defn suspended?
  "Predicate to test if node is terminated."
  [node]
  {:pre [(validate Node node)]}
  (= (:run-state node) :suspended))

(defn stopped?
  "Predicate to test if node is terminated."
  [node]
  {:pre [(validate Node node)]}
  (= (:run-state node) :stopped))

(defn id
  "Return the node's id."
  [node]
  {:pre [(validate Node node)]
   :post [(string? %)]}
  (:id node))

(defn compute-service
  "Return the service provider the node was provided by."
  [node]
  {:pre [(validate Node node)]}
  (:compute-service node))

(defn packager
 "The packager to use on the node"
 [node]
 {:pre [(validate Node node)]}
 (:packager node))

(defn image-user
  "Return the user that is defined by the image."
  [node]
  {:pre [(validate Node node)]}
  (:image-user node))

(defn user
  "Return the user for admin on the node."
  [node]
  {:pre [(validate Node node)]}
  (:user node))

(defn hardware
  "Return a map with `:cpus`, `:ram`, and `:disks` information. The
ram is reported in Mb. The `:cpus` is a sequence of maps, one for each
cpu, containing the number of `:cores` on each. The `:disks` is a
sequence of maps, containing a :size key for each drive, in Gb. Other
keys may be present."
  [node]
  {:pre [(validate Node node)]}
  (:hardware node))

(defn proxy
  "A map with SSH proxy connection details."
  [node]
  {:pre [(validate Node node)]}
  (:proxy node))

(defn node-address
  [node]
  {:pre [(node? node)]}
  (cond
    (primary-ip node) (primary-ip node)
    :else (private-ip node)))


;;; # Functions that require a compute service in the node
(defn taggable?
  "Predicate to test the availability of tags."
  [node]
  (if-let [service (compute-service node)]
    (impl/node-taggable? service node)))

(defn tag
  "Return the specified tag."
  ([node tag-name]
     {:pre [(compute-service node)]}
     (impl/node-tag (compute-service node) node tag-name))
  ([node tag-name default-value]
     {:pre [(compute-service node)]}
     (impl/node-tag (compute-service node) node tag-name default-value)))

(defn tags
  "Return the tags."
  [node]
  {:pre [(compute-service node)]}
  (impl/node-tags (compute-service node) node))

(defn tag!
  "Set a value on the given tag-name."
  [node tag-name value]
  (impl/tag-node! (compute-service node) node tag-name value))

(defn has-base-name?
  "Predicate for the node name matching the specified base-name"
  [node base-name]
  {:pre [(node? node)
         (compute-service node)
         (string? base-name)]}
  (impl/matches-base-name? (compute-service node) (hostname node) base-name))

(defn script-template
  [node]
  {:pre [(validate-node node)]}
  (let [{:keys [os-family os-version packager]}
        (select-keys node [:os-family :os-version :packager])
        context (seq (remove
                      nil?
                      [os-family
                       packager
                       (when (and os-family os-version)
                         (keyword
                          (format "%s-%s" (name os-family) os-version)))]))]
    (debugf "Script context: %s" (pr-str context))
    (when-not context
      (debugf "No script context available: %s" node))
    context))
