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
   [clojure.string :as string]
   [clojure.contrib.condition :as condition]))

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


(def servicegroup-fmt "
define servicegroup {
 servicegroup_name %s
}
")

(def host-service-fmt "
define service {
 use %s
 host_name %s
 service_description %s
 check_command %s
 notification_interval %s
 servicegroups %s
}
")

(def command-fmt "
define command {
 command_name %s
 command_line %s
}
")

(defmulti property-fmt
  (fn [value]
    (cond
     (string? value) :default
     (seq? value) :seq
     (vector? value) :seq
     :else :default)))

(defmethod property-fmt :seq
  [value]
  (string/join "," (map name value)))

(defmethod property-fmt :default
  [value]
  (str value))

(defn define
  "Define a nagios object"
  [object-type properties]
  (format
   "define %s{\n%s\n}\n"
   object-type
   (string/join
    \newline
    (map
     #(format " %s %s" (name (first %)) (property-fmt (second %)))
     properties))))

(defn define-contact [properties]
  (define "contact"
    (select-keys
     properties
     [:contact_name :alias :service_notification_period
      :host_notification_period :service_notification_options
      :host_notification_options :service_notification_commands
      :host_notification_commands :email :contactgroups])))

(defn define-contactgroup [properties]
  (define "contactgroup"
    (select-keys
     properties
     [:contactgroup_name :alias :members :contactgroup_members])))

(defn contact*
  [options]
  (str
   (remote-file/remote-file*
    "/etc/nagios3/conf.d/pallet-contacts.cfg" :action :delete :force true)
   (remote-file/remote-file*
    "/etc/nagios3/conf.d/pallet-contacts.cfg"
    :owner "root"
    :content (string/join
              \newline
              (concat
               (map (comp define-contact first) options)
               (map (comp
                     define-contactgroup
                     #(hash-map :contactgroup_name %))
                    (distinct
                     (map
                      name
                      (apply
                       concat
                       (map (comp :contactgroups first) options))))))))))

(resource/defaggregate contact
  "Define a contact for nagios"
  contact* [options])

(defn hostgroup
  [name alias members]
  (format hostgroup-fmt name alias (string/join "," members)))

(defn host
  ([ip hostname] (host ip hostname "generic-host"))
  ([ip hostname template]
     (format host-fmt template hostname hostname ip)))

(defn define-servicegroup
  "Define a nagios servicegroup"
  [properties]
  (when-not
      (every? properties #{:servicegroup_name})
    (condition/raise
     :type :invalid-servicegroup-definiton
     :message (format "Invalid servicegroup definition : %s" properties)))
  (define "servicegroup"
    (select-keys
     properties
     [:servicegroup_name :alias :members :servicegroup_members :notes
      :notes_url :action_url])))

(defn define-service
  "Define a nagios service"
  [properties]
  (when-not
      (every? properties #{:service_description :host_name :check_command})
    (condition/raise
     :type :invalid-service-definiton
     :message (format "Invalid service definition : %s" properties)))
  (define "service"
    (select-keys
     properties
     [:use :host_name :hostgroup_name :service_description :display_name
      :servicegroups :is_volatile :check_command :initial_state
      :max_check_attempts :check_interval :retry_interval :active_checks_enabled
      :passive_checks_enabled :check_period :obsess_over_service
      :check_freshness :freshness_threshold :event_handler
      :event_handler_enabled :low_flap_threshold :high_flap_threshold
      :flap_detection_enabled :flap_detection_options :process_perf_data
      :retain_status_information :retain_nonstatus_information
      :notification_interval :first_notification_delay :notification_period
      :notification_options :notifications_enabled :contacts :contact_groups
      :stalking_options :notes :notes_url :action_url :icon_image
      :icon_image_alt])))

;; (defn service
;;   ([hostgroup description command notification-interval]
;;      (service
;;       hostgroup description command notification-interval "generic-service"))
;;   ([hostgroup description command notification-interval template]
;;      (format
;;       service-fmt
;;       template hostgroup description command notification-interval)))

;; (defn servicegroup
;;   [servicegroup-name]
;;   (format servicegroup-fmt servicegroup-name))

(defn host-service
  [hostname {:keys [template check_command service_description
                    notification_interval service_group]
             :as options}]
  (define-service
    (-> (merge
         {:use "generic-service"
          :notification_interval 0}
         options)
        (assoc :host_name hostname))))

(defn config-for-node
  [node]
  (parameter/get-for [:nagios :host-services (keyword (.getName node))] nil))

(defn hosts*
  []
  (str
   (remote-file/remote-file*
    "/etc/nagios3/conf.d/pallet-host-*.cfg" :action :delete :force true)
   (string/join
    \newline
    (for [node (filter config-for-node (target/all-nodes))
          :let [hostname (.getName node)]]
      (remote-file/remote-file*
       (format "/etc/nagios3/conf.d/pallet-host-%s.cfg" hostname)
       :owner "root"
       :content (str
                 (host
                  (compute/primary-ip node) hostname)
                 (string/join
                  \newline
                  (map
                   (partial host-service hostname)
                   (config-for-node node)))))))))

(resource/defresource hosts
  "Define nagios hosts"
  hosts* [])

(defn servicegroup*
  "A nagios servicegroup definition"
  [options]
  (parameter/update-default!
   [:default :nagios :servicegroups (keyword (:servicegroup_name options))]
   (fn [x]
     (merge-with
      conj
      (merge (or x {}) (dissoc options :members :servicegroup_members))
      (select-keys options [:members :servicegroup_members])))))

(resource/deflocal servicegroup
  "Configure nagios servicegroup monitoring.
    :servicegroup_name    servicegroup name
    :alias                alias
    :members              services
    :servicegroup_members servicegroups
    :notes                note string
    :notes_url            url
    :action_url           url"
  servicegroup* [options])

(defn servicegroups*
  []
  (str
   (remote-file/remote-file*
    "/etc/nagios3/conf.d/pallet-servicegroups.cfg"
    :owner "root"
    :content
    (string/join
     \newline
     (map
      (comp
       define-servicegroup
       #(parameter/get-for
         [:nagios :servicegroups %]
         {:servicegroup_name (name %)}))
      (distinct
       (map
        keyword
        (filter
         identity
         (apply
          concat
          (apply
           concat
           (map
            #(map :servicegroups (config-for-node %))
            (target/all-nodes))))))))))))

(resource/defresource servicegroups
  servicegroups* [])

(defn command*
  [[command-name command-line]]
  (remote-file/remote-file*
   (format "/etc/nagios3/conf.d/pallet-command-%s.cfg" (name command-name))
   :owner "root"
   :content (format command-fmt (name command-name) command-line)
   :literal true))

(defn command
  "Define nagios command"
  [command-name command-line]
  (remote-file/remote-file
   (format "/etc/nagios3/conf.d/pallet-command-%s.cfg" (name command-name))
   :owner "root"
   :content (format command-fmt (name command-name) command-line)
   :literal true))

(defn commands*
  []
  (string/join
   "\n"
   (map command* (parameter/get-for [:nagios :commands] nil))))

(resource/defresource commands
  "Define nagios commands"
  commands* [])

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

(defn hostgroups
  []
  (let [nodes (filter config-for-node (target/all-nodes))]
    (reduce
     #(str
       %1 " "
       (hostgroup
        (first %2) (first %2)
        (map (fn [n] (.getName n)) (second %2))))
     (hostgroup "all-managed" "Managed Servers" (map #(.getName %) nodes))
     (group-by (fn [n] (.getTag n)) nodes))))


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
   (commands)

   (remote-file/remote-file
    "/etc/nagios3/conf.d/pallet-hostgroups.cfg"
    :owner "root"
    :content (arguments/delayed-fn hostgroups))

   ;; Generate hostgroup configurations for each tag
   ;; (remote-file/remote-file
   ;;  "/etc/nagios3/conf.d/pallet-services.cfg"
   ;;  :owner "root"
   ;;  :content (service "all-managed" "SSH" "check_ssh" 0))

   ;; generate host configurations for each node
   (hosts)
   (servicegroups)))
