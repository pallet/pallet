(ns pallet.crate.rabbitmq
  (:require
   [pallet.resource.package :as package]
   [pallet.resource.remote-file :as remote-file]
   [pallet.crate.etc-default :as etc-default]
   [pallet.crate.iptables :as iptables]
   [pallet.parameter :as parameter]
   [clojure.contrib.json :as json])
  (:use
   pallet.thread-expr))

(def etc-default-keys
  {:mnesia-base :MNESIA_BASE
   :log-base :LOG_BASE
   :nodename :NODENAME
   :node-ip-addres :NODE_IP_ADDRESS
   :node-port :NODE_PORT
   :node-count :NODE_COUNT
   :config-file :CONFIG_FILE})


(defn rabbitmq
  "Install rabbitmq from packages."
  [request & {:keys [node node-count mnesia-base log-base node-ip-address
                     node-port config-file]
              :as options}]
  (->
   request
   (package/package "rabbitmq-server")
   (apply-> etc-default/write "rabbitmq"
            (apply concat
                   (map #(vector (etc-default-keys (first %)) (second %))
                        (select-keys options (keys etc-default-keys)))))
   (parameter/assoc-for-target
    [:rabbitmq :default] options
    [:rabbitmq :config-file] (or config-file "/etc/rabbitmq/rabbitmq.conf"))))

(defn configure
  "Write the configuration file, based on a hash map m, that is serialised
   as json."
  [request m]
  (-> request
      (remote-file/remote-file
       (parameter/get-for-target request [:rabbitmq :config-file])
       :content (json/json-str m)
       :literal true)))

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
