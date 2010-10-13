(ns pallet.crate.rabbitmq
  (:require
   [pallet.resource.package :as package]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.directory :as directory]
   [pallet.resource.remote-file :as remote-file]
   [pallet.crate.etc-default :as etc-default]
   [pallet.crate.etc-hosts :as etc-hosts]
   [pallet.crate.iptables :as iptables]
   [pallet.parameter :as parameter]
   [pallet.compute :as compute]
   [pallet.request-map :as request-map]
   [clojure.string :as string])
  (:use
   pallet.thread-expr))

(def ^{:doc "Settings for the daemon"}
  etc-default-keys
  {:node-count :NODE_COUNT
   :rotate-suffix :ROTATE_SUFFIX
   :user :USER
   :name :NAME
   :desc :DESC
   :init-log-dir :INIT_LOG_DIR
   :daemon :DAEMON})

(def ^{:doc "RabbitMQ conf settings"} conf-keys
  {:mnesia-base :MNESIA_BASE
   :log-base :LOG_BASE
   :nodename :NODENAME
   :node-ip-addres :NODE_IP_ADDRESS
   :node-port :NODE_PORT
   :config-file :CONFIG_FILE
   :server-start-args :SERVER_START_ARGS
   :multi-start-args :MULTI_START_ARGS
   :ctl-erl-args :CTL_ERL_ARGS})

(defmulti erlang-config-format class)
(defmethod erlang-config-format :default
  [x]
  (str x))

(defmethod erlang-config-format clojure.lang.Named
  [x]
  (name x))

(defmethod erlang-config-format java.lang.String
  [x]
  (str "'" x "'"))

(defmethod erlang-config-format java.util.Map$Entry
  [x]
  (str
   "{" (erlang-config-format (key x)) ", " (erlang-config-format (val x)) "}"))

(defmethod erlang-config-format clojure.lang.ISeq
  [x]
  (str "[" (string/join "," (map erlang-config-format x)) "]"))

(defmethod erlang-config-format clojure.lang.IPersistentMap
  [x]
  (str "[" (string/join "," (map erlang-config-format x)) "]"))

(defn erlang-config [m]
  (str (erlang-config-format m) "."))

(defn- cluster-nodes
  "Create a node list for the specified nodes"
  [node-name nodes]
  (map
   (fn cluster-node-name [node]
     (str node-name "@" (compute/hostname node)))
   nodes))

(defn- cluster-nodes-for-tag
  "Create a node list for the specified tag"
  [request tag]
  (let [nodes (request-map/nodes-in-tag request tag)]
    (assert (seq nodes))
    (cluster-nodes
     (parameter/get-for
      request
      [:host (keyword (.getId (first nodes))) :rabbitmq :options :node-name]
      "rabbit")
     nodes)))

(defn- default-cluster-nodes
  [request options]
  (cluster-nodes
   (:node-name options "rabbit")
   (request-map/nodes-in-tag request)))

(defn- configure
  "Write the configuration file, based on a hash map m, that is serialised as
   erlang config.  By specifying :cluster tag, the current tag's rabbitmq
   instances will be added as ram nodes to that cluster."
  [request cluster config]
  (let [options (parameter/get-for-target request [:rabbitmq :options] nil)
        cluster-nodes (when cluster (cluster-nodes-for-tag request cluster))
        cluster-nodes (or cluster-nodes
                          (if-let [node-count (:node-count options)]
                            (when (> node-count 1)
                              (default-cluster-nodes request options))))]
    (->
     request
     (etc-hosts/hosts-for-tag (request-map/tag request))
     (when->
      (or cluster-nodes config)
      (remote-file/remote-file
       (parameter/get-for-target request [:rabbitmq :config-file])
       :content (erlang-config
                 (if cluster-nodes
                   (assoc-in config [:rabbit :cluster_nodes] cluster-nodes)
                   config))
       :literal true))
     (when->
      cluster
      (etc-hosts/hosts-for-tag cluster)))))

(defn rabbitmq
  "Install rabbitmq from packages.
    :config map   - erlang configuration, specified as a map
                    from application to attribute value map.
    :cluster tag  - If specified, then this tag will be ram nodes for the
                    given tag's disk cluster."
  [request & {:keys [node node-count mnesia-base log-base node-ip-address
                     node-port config-file config cluster]
              :as options}]
  (->
   request
   (parameter/assoc-for-target
    [:rabbitmq :options] options
    [:rabbitmq :default-file] (or config-file "/etc/default/rabbitmq")
    [:rabbitmq :conf-file] (or config-file "/etc/rabbitmq/rabbitmq.conf")
    [:rabbitmq :config-file] (or config-file "/etc/rabbitmq/rabbitmq.config"))
   (directory/directory
    "/etc/rabbitmq")
   (apply-map->
    etc-default/write "rabbitmq"
    (map #(vector (etc-default-keys (first %)) (second %))
         (select-keys options (keys etc-default-keys))))
   (apply-map->
    etc-default/write "/etc/rabbitmq/rabbitmq.conf"
    (map #(vector (conf-keys (first %)) (second %))
         (select-keys options (keys conf-keys))))
   (package/package "rabbitmq-server")
   (configure cluster config)
   (etc-hosts/hosts)))

(defn iptables-accept
  "Accept rabbitmq connectios, by default on port 5672"
  ([request] (iptables-accept request 5672))
  ([request port]
     (iptables/iptables-accept-port request port)))

(defn iptables-accept-status
  "Accept rabbitmq status connections, by default on port 55672"
  ([request] (iptables-accept request 55672))
  ([request port]
     (iptables/iptables-accept-port request port)))

(defn password
  "Change rabbitmq password."
  [request user password]
  (->
   request
   (exec-script/exec-checked-script
    "Change RabbitMQ password"
    (rabbitmqctl change_password ~user ~password))))

;; rabbitmq without iptablse
#_
(pallet.core/defnode a {}
  :bootstrap (pallet.resource/phase
              (pallet.crate.automated-admin-user/automated-admin-user))
  :configure (pallet.resource/phase
              (pallet.crate.rabbitmq/rabbitmq))
  :rabbitmq-restart (pallet.resource/phase
                     (pallet.resource.service/service
                      "rabbitmq-server" :action :restart)))

;; cluster
#_
(pallet.core/defnode a {}
  :bootstrap (pallet.resource/phase
              (pallet.crate.automated-admin-user/automated-admin-user))
  :configure (pallet.resource/phase
              (pallet.crate.rabbitmq/rabbitmq))
  :rabbitmq-restart (pallet.resource/phase
                     (pallet.resource.service/service
                      "rabbitmq-server" :action :restart)))

#_
(pallet.core/defnode ram-nodes {}
  :bootstrap (pallet.resource/phase
              (pallet.crate.automated-admin-user/automated-admin-user))
  :configure (pallet.resource/phase
              (pallet.crate.rabbitmq/rabbitmq))
  :rabbitmq-restart (pallet.resource/phase
                     (pallet.resource.service/service
                      "rabbitmq-server" :action :restart)))

;; rabbitmq with iptables
#_
(pallet.core/defnode a {}
  :bootstrap (pallet.resource/phase
              (pallet.crate.automated-admin-user/automated-admin-user))
  :configure (pallet.resource/phase
              (pallet.crate.iptables/iptables-accept-icmp)
              (pallet.crate.iptables/iptables-accept-established)
              (pallet.crate.ssh/iptables-throttle)
              (pallet.crate.ssh/iptables-accept)
              (pallet.crate.rabbitmq/rabbitmq :node-count 2)
              (pallet.crate.rabbitmq/iptables-accept))
  :rabbitmq-restart (pallet.resource/phase
                     (pallet.resource.service/service
                      "rabbitmq-server" :action :restart)))
