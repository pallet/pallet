(ns pallet.crate.nagios
  (:require
   [pallet.compute :as compute]
   [pallet.resource :as resource]
   [pallet.request-map :as request-map]
   [pallet.argument :as argument]
   [pallet.parameter :as parameter]
   [pallet.resource.package :as package]
   [pallet.resource.user :as user]
   [pallet.resource.remote-file :as remote-file]
   [clojure.string :as string]
   [clojure.contrib.condition :as condition]))

(defn nagios-hostname
  "Return the nagios hostname for a node"
  [node]
  ;; this should match (request-map/safe-name request)
  ;; for things to work well
  (format "%s%s" (compute/tag node) (request-map/safe-id (compute/id node))))

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


(resource/defcollect contact
  "Define a contact for nagios"
  (contact*
   [request options]
   (str
    (remote-file/remote-file*
     request
     "/etc/nagios3/conf.d/pallet-contacts.cfg" :action :delete :force true)
    (remote-file/remote-file*
     request
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
                        (map (comp :contactgroups first) options)))))))))))

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
  [request node]
  (parameter/get-for request
   [:nagios :host-services (keyword (nagios-hostname node))] nil))

(defn config-for-unmanaged-node
  [request unmanaged-node]
  (parameter/get-for request
   [:nagios :host-services (keyword (:name unmanaged-node))] nil))

(defn unmanaged-host
  "Add an unmanaged host to a nagios server. Beware name conflicts between
   managed and unmanged servers are not detected."
  [request ip name]
  (parameter/update-for
   request [:nagios :hosts]
   (fn [m]
     (distinct (conj (or m []) {:ip ip :name name})))))

(resource/defresource hosts
  "Define nagios hosts"
  (hosts*
   [request]
   ;(swank.core/break)
   (str
    (remote-file/remote-file*
     request
     "/etc/nagios3/conf.d/pallet-host-*.cfg" :action :delete :force true)
    (string/join
     \newline
     (for [node (filter #(config-for-node request %) (:all-nodes request))
           :let [hostname (nagios-hostname node)]]
       (remote-file/remote-file*
        request
        (format "/etc/nagios3/conf.d/pallet-host-%s.cfg" hostname)
        :owner "root"
        :content (str
                  (host
                   (compute/primary-ip node) hostname)
                  (string/join
                   \newline
                   (map
                    (partial host-service hostname)
                    (config-for-node request node)))))))
    (string/join
     \newline
     (for [node (filter
                 #(config-for-unmanaged-node request %)
                 (parameter/get-for request [:nagios :hosts] nil))
           :let [hostname (:name node)]]
       (remote-file/remote-file*
        request
        (format "/etc/nagios3/conf.d/pallet-host-%s.cfg" hostname)
        :owner "root"
        :content (str
                  (host (:ip node) hostname)
                  (string/join
                   \newline
                   (map
                    (partial host-service hostname)
                    (config-for-unmanaged-node request node))))))))))

(defn servicegroup
  "Configure nagios servicegroup monitoring.
    :servicegroup_name    servicegroup name
    :alias                alias
    :members              services
    :servicegroup_members servicegroups
    :notes                note string
    :notes_url            url
    :action_url           url"
  [request options]
  (parameter/update-for
   request [:nagios :servicegroups (keyword (:servicegroup_name options))]
   (fn [x]
     (merge-with
      conj
      (merge (or x {}) (dissoc options :members :servicegroup_members))
      (select-keys options [:members :servicegroup_members])))))


(resource/defresource servicegroups
  (servicegroups*
   [request]
   (str
    (remote-file/remote-file*
     request
     "/etc/nagios3/conf.d/pallet-servicegroups.cfg"
     :owner "root"
     :content
     (string/join
      \newline
      (map
       (comp
        define-servicegroup
        #(parameter/get-for
          request
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
             #(map :servicegroups %)
             (concat
              (map #(config-for-node request %) (:all-nodes request))
              (map #(config-for-unmanaged-node request %)
                   (parameter/get-for
                    request [:nagios :hosts] nil)))))))))))))))

(defn command*
  [request [command-name command-line]]
  (remote-file/remote-file*
   request
   (format "/etc/nagios3/conf.d/pallet-command-%s.cfg" (name command-name))
   :owner "root"
   :content (format command-fmt (name command-name) command-line)
   :literal true))

(defn command
  "Define nagios command"
  [request command-name command-line]
  (remote-file/remote-file
   request
   (format "/etc/nagios3/conf.d/pallet-command-%s.cfg" (name command-name))
   :owner "root"
   :content (format command-fmt (name command-name) command-line)
   :literal true))

(resource/defresource commands
  "Define nagios commands"
  (commands*
   [request]
   (string/join
    "\n"
    (map
     #(command* request %)
     (parameter/get-for request [:nagios :commands] nil)))))

(defn nrpe-command
  "Define an nrpe command"
  [request]
  (command
   request
   "check_nrpe"
   "$USER1$/check_nrpe -H $HOSTADDRESS$ -c $ARG1$"))

(defn record-nagios-server
  "Record nagios server details"
  [request]
  (parameter/update-for
   request [:nagios :server :ip]
   (fn [x]
     (compute/primary-ip (:target-node request)))))

(defn hostgroups
  "Create host groups for each tag, and one for all managed machines."
  [request]
  (let [nodes (filter #(config-for-node request %) (:all-nodes request))]
    (str
     (hostgroup "all-managed" "Managed Servers" (map nagios-hostname nodes))
     (when-let  [unmanaged (parameter/get-for
                            request [:nagios :hosts] nil)]
       (hostgroup
        "all-unmanaged" "Unmanaged Servers"
        (map :name unmanaged)))
     (reduce
      #(str
        %1 " "
        (hostgroup
         (first %2) (first %2)
         (map (fn [n] (nagios-hostname n)) (second %2))))
      ""
      (group-by (fn [n] (compute/tag n)) nodes)))))


(defn nagios
  "Install nagios. Depends on a MTA and a webserver.  Note that you will
   need all target node groups in the lift/converge command."
  [request admin-password]
  (->
   request
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

   ;; remove any botched configuration files
   (remote-file/remote-file
    "/etc/nagios3/conf.d/pallet-hostgroups.cfg" :action :delete :force true)
   (remote-file/remote-file
    "/etc/nagios3/conf.d/pallet-servicegroups.cfg" :action :delete :force true)

   (resource/execute-pre-phase
    (record-nagios-server))

   ;; Generate hostgroups defintion for each tag
   (resource/execute-after-phase
    (commands)

    (remote-file/remote-file
     "/etc/nagios3/conf.d/pallet-hostgroups.cfg"
     :owner "root"
     :content (argument/delayed-fn hostgroups))

    ;; generate host configurations for each node
    (hosts)
    (servicegroups))))
