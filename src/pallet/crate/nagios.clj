(ns pallet.crate.nagios
  (:require
   [pallet.target :as target]
   [pallet.compute :as compute]
   [pallet.resource :as resource]
   [pallet.arguments :as arguments]
   [pallet.parameter :as parameter]
   [pallet.resource.package :as package]
   [pallet.resource.user :as user]
   [pallet.resource.remote-file :as remote-file]
   [clojure.string :as string]))

(def hostgroup-fmt "
define hostgroup {
  hostgroup_name %s
  alias %s
  members %s
  }
")

(def host-fmt "
define host {
 use %s
 host_name %s
 alias %s
 address %s
}
")


(def service-fmt "
define service {
 use %s
 hostgroup_name %s
 service_description %s
 check_command %s
 notification_interval %s
}
")

(def host-service-fmt "
define service {
 use %s
 host_name %s
 service_description %s
 check_command %s
 notification_interval %s
}
")

(def command-fmt "
define command {
 command_name %s
 command_line %s
}
")

(defn hostgroup
  [name alias members]
  (format hostgroup-fmt name alias (string/join "," members)))

(defn host
  ([ip hostname] (host ip hostname "generic-host"))
  ([ip hostname template]
     (format host-fmt template hostname hostname ip)))

(defn service
  ([hostgroup description command notification-interval]
     (service
      hostgroup description command notification-interval "generic-service"))
  ([hostgroup description command notification-interval template]
     (format
      service-fmt
      template hostgroup description command notification-interval)))

(defn host-service
  [hostname {:keys [template command service-description notification-interval]
             :or {template "generic-service" notification-interval 0}}]
  (format
   host-service-fmt
   template hostname service-description command notification-interval))

(defn hosts*
  []
  (str
   (remote-file/remote-file*
    "/etc/nagios3/conf.d/pallet-host-*.cfg" :action :delete :force true)
   (string/join
    \newline
    (for [node (target/target-nodes)
          :let [hostname (.getName node)]]
      (remote-file/remote-file*
       (format "/etc/nagios3/conf.d/pallet-host-%s.cfg" hostname)
       :owner "root"
       :content (str
                 (host
                  (compute/primary-ip node) hostname)
                 (string/join
                  \newline
                  (map (partial host-service hostname)
                       (parameter/get-for
                        [:nagios :host-services (keyword hostname)])))))))))

(resource/defresource hosts
  "Define nagios hosts"
  hosts* [])

(defn command
  "Define nagios command"
  [name command-line]
  (remote-file/remote-file
   (format "/etc/nagios3/conf.d/pallet-command-%s.cfg" name)
   :owner "root"
   :content (format command-fmt name command-line)
   :literal true))

(defn nrpe-command
  "Define an nrpe command"
  []
  (command
   "check_nrpe"
   "$USER1$/check_nrpe -H $HOSTADDRESS$ -c $ARG1$"))

(defn record-nagios-server*
  []
  (parameter/update-default!
   [:default :nagios :server :ip]
   (fn [x]
     (compute/primary-ip (target/node)))))

(resource/deflocal record-nagios-server
  "Record nagios server details"
  record-nagios-server* [])

(defn nagios
  "Install nagios. Depends on a MTA and a webserver.  Note that you will
   need all target node groups in the lift/converge command."
  [admin-password]
  (package/package-manager
   :debconf
   (str "nagios3-cgi nagios3/adminpassword password " admin-password)
   (str "nagios3-cgi nagios3/adminpassword-repeat password " admin-password)
   (str "nagios3-cgi nagios3/httpd multiselect " "apache2"))
  (package/packages
   :yum ["httpd" "gcc" "glib" "glibc-common" "gd" "gd-devel" "nagios"]
   :aptitude ["build-essential" "libgd2-xpm-dev" "apache2"
              "php5-common" "php5" "libapache2-mod-php5"
              "nagios3" "nagios-plugins-extra nagios-nrpe-plugin"])
  ;; (user/group "nagcmd" :system true)
  ;; (user/user "nagios" :system true :shell "/usr/bin/false" :groups "nagcmd")

  (resource/execute-pre-phase
   (record-nagios-server))

  ;; Generate hostgroups defintion for each tag
  (resource/execute-after-phase
   (remote-file/remote-file
    "/etc/nagios3/conf.d/pallet-hostgroups.cfg"
    :owner "root"
    :content (arguments/delayed
              (reduce
               #(str
                 %1 " "
                 (hostgroup
                  (first %2) (first %2)
                  (map (fn [n] (.getName n)) (second %2))))
               (string/join
                \newline
                [;(hostgroup "all" "All Servers" ["*"])
                 (hostgroup "all-managed" "Managed Servers"
                            (map #(.getName %) (target/target-nodes)))])
               (group-by (fn [n] (.getTag n)) (target/target-nodes)))))

   ;; Generate hostgroup configurations for each tag
   (remote-file/remote-file
    "/etc/nagios3/conf.d/pallet-services.cfg"
    :owner "root"
    :content (service "all-managed" "SSH" "check_ssh" 0))

   ;; generate host configurations for each node
   (hosts)

   ;; generate
   ))
