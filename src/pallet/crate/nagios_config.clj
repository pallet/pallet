(ns pallet.crate.nagios-config
  (:require
   [pallet.resource :as resource]
   [pallet.arguments :as arguments]
   [pallet.resource.file :as file]
   [pallet.resource.package :as package]
   [pallet.crate.iptables :as iptables]
   [pallet.target :as target]
   [pallet.parameter :as parameter]
   [clojure.string :as string]))

(defn service*
  "A nagios service definition"
  [{:keys [host_name]
    :as options}]
  (parameter/update-default!
   [:default :nagios :host-services
    (keyword (or host_name (.getName (target/node))))]
   (fn [x]
     (distinct
      (conj
       (or x [])
       options)))))

(resource/deflocal service
  "Configure nagios service monitoring.
     :servicegroups        name for service group(s) service should be part of
     :check_command        command for service
     :service_description  description for the service"
  service* [options])

(defn command*
  [& {:keys [command_name command_line] :as options}]
  (parameter/update-default!
   [:default :nagios :commands (keyword command_name)]
   (fn [_] command_line)))

(resource/deflocal command
  "Configure nagios command monitoring.
     :command_name name
     :command_line command line"
  command* [& options])

(defn nrpe-client
  "Configure nrpe on machine to be monitored"
  []
  (package/package "nagios-nrpe-server")
  (file/sed
   "/etc/nagios/nrpe.cfg"
   (arguments/delayed
    {"allowed_hosts=127.0.0.1"
     (format "allowed_hosts=%s" (parameter/get-for [:nagios :server :ip]))})
   {}))

(defn nrpe-client-port
  "Open the nrpe client port to the nagios server ip"
  []
  (iptables/iptables-accept-port
   5666 "tcp" :source (parameter/lookup :nagios :server :ip)))

(defn nrpe-check-load
  []
  (service
   {:servicegroups [:machine]
    :check_command "check_nrpe_1arg!check_load"
    :service_description  "Current Load"}))

(defn nrpe-check-users
  []
  (service
   {:servicegroups [:machine]
    :check_command "check_nrpe_1arg!check_users"
    :service_description  "Current Users"}))

(defn nrpe-check-disk
  []
  (service
   {:servicegroups [:machine]
    :check_command "check_nrpe_1arg!check_hda1"
    :service_description  "Root Disk"}))

(defn nrpe-check-total-procs
  []
  (service
   {:servicegroups [:machine]
    :check_command "check_nrpe_1arg!check_total_procs"
    :service_description  "Total Processes"}))

(defn nrpe-check-zombie-procs
  []
  (service
   {:servicegroups [:machine]
    :check_command "check_nrpe_1arg!check_zombie_procs"
    :service_description  "Zombie Processes"}))

(def check-http-options
  #{:port :ssl :use-ipv4 :use-ipv6 :timeout :no-body :url
    :expect :string :method :certificate})

(defn dissoc-keys
  [m keys]
  (apply dissoc m keys))

(defn monitor-http
  "Configure nagios monitoring of https certificate"
  [& {:keys [port ssl use-ipv4 use-ipv6 timeout no-body url expect string
             method]
      :or {timeout 10}
      :as options}]
  (let [cmd (str
             "check_http_"
             (string/join
              ""
              (map name (filter check-http-options (keys options)))))]
    (command
     :command_name cmd
     :command_line
     (format
      "/usr/lib/nagios/plugins/check_http -I '$HOSTADDRESS$' %s --timeout=%s"
      (str
       (when port (format " --port=%d" port))
       (when ssl " --ssl")
       (when no-body " --no-body")
       (when use-ipv4 " --use-ipv4")
       (when use-ipv6 " --use-ipv6")
       (when url (format " --url=%s" url))
       (when expect (format " --expect=%s" expect))
       (when string (format " --string=%s" string))
       (when method (format " --method=%s" method)))
      timeout))
    (service
     (merge
      {:servicegroups [:http-services]
       :service_description (if ssl "HTTPS" "HTTP")}
      (->
       options
       (dissoc-keys check-http-options)
       (assoc :check_command cmd))))))

(defn monitor-https-certificate
  "Configure nagios monitoring of https certificate"
  [& {:keys [port ssl use-ipv4 use-ipv6 timeout certificate]
      :or {timeout 10 certificate 14}
      :as options}]
  (let [cmd (str
             "check_https_certificate"
             (string/join
              ""
              (map name (filter check-http-options (keys options)))))]
    (command
     :command_name cmd
     :command_line
     (format
      "/usr/lib/nagios/plugins/check_http -I '$HOSTADDRESS$' %s --timeout=%s"
      (str
       (when port (format " --port=%d" port))
       (when ssl " --ssl")
       (when use-ipv4 " --use-ipv4")
       (when use-ipv6 " --use-ipv6")
       (format " --certificate=%d" certificate))
      timeout))
    (service
     (merge
      {:servicegroups [:http-services]
       :service_description "HTTPS Certificate"}
      (->
       options
       (dissoc-keys check-http-options)
       (assoc :check_command cmd))))))
