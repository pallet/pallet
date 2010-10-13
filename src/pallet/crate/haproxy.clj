(ns pallet.crate.haproxy
  "HA Proxy installation and configuration"
  (:require
   [pallet.argument :as argument]
   [pallet.compute :as compute]
   [pallet.parameter :as parameter]
   [pallet.request-map :as request-map]
   [pallet.resource :as resource]
   [pallet.resource.package :as package]
   [pallet.resource.remote-file :as remote-file]
   [pallet.crate.etc-default :as etc-default]
   [pallet.target :as target]
   [clojure.string :as string]
   [clojure.contrib.logging :as logging]
   clojure.set)
  (:use
   [clojure.contrib.core :only [-?>]]
   pallet.thread-expr))

(def conf-path "/etc/haproxy/haproxy.cfg")

(def haproxy-user "haproxy")
(def haproxy-group "haproxy")

(def default-global
  {:log ["127.0.0.1 local0" "127.0.0.1 local1 notice"]
   :maxconn 4096
   :user "haproxy"
   :group "haproxy"
   :daemon true})

(def default-defaults
  {:log "global"
   :mode "http"
   :option ["httplog" "dontlognull" "redispatch"]
   :retries 3
   :maxconn 2000
   :contimeout 5000
   :clitimeout 50000
   :srvtimeout 50000})

(defn install-package
  "Install HAProxy from packages"
  [request]
  (-> request
      (when->
       (= :amzn-linux (request-map/os-family request))
       (package/add-epel request "5-4"))
      (package/package "haproxy")))

(defmulti format-kv (fn format-kv-dispatch [k v & _] (class v)))

(defmethod format-kv :default
  [k v sep]
  (format "%s %s%s" (name k) v sep))

(defmethod format-kv clojure.lang.IPersistentVector
  [k v sep]
  (reduce (fn format-kv-vector [s value] (str s (format-kv k value sep))) "" v))

(defmethod format-kv clojure.lang.Sequential
  [k v sep]
  (reduce (fn format-kv-vector [s value] (str s (format-kv k value sep))) "" v))

(defmethod format-kv Boolean
  [k v sep]
  (when v (format "%s%s" (name k) sep)))

(defn- config-values
  "Format a map as key value pairs"
  [m]
  (apply str (for [[k v] m] (format-kv k v \newline))))

(defn- config-section
  [[key values]]
  (if (= :listen key)
    (reduce
     #(str
       %1
       (format
        "%s %s %s\n%s"
        (name key) (name (first %2)) (:server-address (second %2))
        (config-values (dissoc (second %2) :server-address))))
     ""
     values)
    (format "%s\n%s" (name key) (config-values values))))

(defn- config-server
  "Format a server configuration line"
  [m]
  {:pre [(:name m) (:ip m)]}
  (format
   "%s %s%s %s"
   (name (:name m))
   (:ip m)
   (if-let [p (:server-port m)] (str ":" p) "")
   (apply
    str
    (for [[k v] (dissoc m :server-port :ip :name)]
      (format-kv k v " ")))))

(defn merge-servers
  [request options]
  (let [apps (map keyword (keys (:listen options)))
        tag (keyword (request-map/tag request))
        srv-apps (-?> request :parameters :haproxy tag)
        app-keys (keys srv-apps)
        unconfigured (clojure.set/difference (set app-keys) (set apps))
        no-nodes (clojure.set/difference (set app-keys) (set apps))]
    (when (seq unconfigured)
      (doseq [app unconfigured]
        (logging/warn
         (format
          "Unconfigured proxy %s %s"
          tag app))))
    (when (seq no-nodes)
      (doseq [app no-nodes]
        (logging/warn
         (format
          "Configured proxy %s %s with no servers"
          tag app))))
    (reduce
     #(update-in %1 [:listen (first %2) :server]
                 (fn [servers]
                   (concat
                    (or servers [])
                    (map config-server (second %2)))))
     options
     srv-apps)))

(defn configure
  "Configure HAProxy.
   :global and :defaults both take maps of keyword value pairs. :listen takes a
   map where the keys are of the form \"name\" and contain an :address key with
   a string containing ip:port, and other keyword/value. Servers for each listen
   section can be declared with the proxied-by function."
  [request & {:keys
              [global defaults listen frontend backend]
              :as options}]
  (->
   request
   (remote-file/remote-file
    conf-path
    :content (argument/delayed
              [request]
              (let [combined (merge
                              {:global default-global
                               :defaults default-defaults}
                              (merge-servers request options))]
                (string/join
                 (map
                  config-section
                  (map
                   (juxt identity combined)
                   (filter
                    combined
                    [:global :defaults :listen :frontend :backend]))))))
    :literal true)
   (etc-default/write "haproxy" :ENABLED 1)))


(defn proxied-by
  "Declare that a node is proxied by the given haproxy server.

   (proxied-by request :haproxy :app1 :check true)."
  [request proxy-tag proxy-group
   & {:keys [server-port addr backup check cookie disabled fall id
             inter fastinter downinter maxqueue minconn port redir
             rise slowstart source track weight]
      :as options}]
  (->
   request
   (parameter/update-for
    [:haproxy (keyword proxy-tag) (keyword proxy-group)]
    (fn [v]
      (conj
       (or v [])
       (merge
        options
        {:ip (request-map/target-ip request)
         ;; some providers don't allow for node names, only node ids
         :name (or (request-map/target-name request)
                   (target/safe-id (request-map/target-id request)))}))))))

#_
(pallet.core/defnode haproxy
  {}
  :bootstrap (pallet.resource/phase
              (pallet.crate.automated-admin-user/automated-admin-user))
  :configure (pallet.resource/phase
              (pallet.crate.haproxy/install-package)
              (pallet.crate.haproxy/configure)))
