(ns pallet.crate.ganglia
  "Install and configure ganglia."
  (:require
   [pallet.arguments :as arguments]
   [pallet.compute :as compute]
   [pallet.target :as target]
   [pallet.resource :as resource]
   [pallet.stevedore :as stevedore]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.file :as file]
   [pallet.resource.package :as package]
   [pallet.crate.nagios-config :as nagios-config]
   [clojure.string :as string]))

(defn install
  []
  (package/packages
   :aptitude ["rrdtool" "librrds-perl" "librrd2-dev" "php5-gd"
              "ganglia-monitor" "ganglia-webfrontend" "gmetad"])
  (file/symbolic-link
   "/usr/share/ganglia-webfrontend" "/var/www/ganglia"))

(defn monitor
  []
  (package/packages
   :aptitude ["ganglia-monitor"]))

(defn data-source
  [[id {:keys [interval hosts]
        :or {interval 15}}]]
  (format
   "data_source \"%s\" %d %s\n"
   id interval
   (string/join " " (if (or (seq? hosts) (vector? hosts))
                      hosts
                      (map
                       compute/primary-ip
                       (target/nodes-in-tag hosts))))))
(defn configure*
  [{:keys [data_sources rras trusted_hosts]
      :as options}]
  (str
   (reduce #(str %1 (data-source %2)) "" data_sources)
   (when rras
     (reduce #(str %1 (format " \"%s\"" %2)) "RRAs" rras))
   (when trusted_hosts
     (reduce #(str %1 (format " %s" %2)) "trusted_hosts" trusted_hosts))
   (reduce
    #(str %1 (format "%s %s" (name (first %2)) (second %2)))
    ""
    (select-keys
     options
     [:scalable :gridname :authority :all_trusted
      :setuid :setuid_username :xml_port :interactive_port
      :server_threads :rrd_rootdir]))))

(defn configure
  "Each data source is a map, keyed by data source name.
     :interval   (15s)
     :hosts      list of hosts, or tag name"
  [& {:keys [data_sources]
      :as options}]
  (remote-file/remote-file
   "/etc/ganglia/gmetad.conf"
   :content (arguments/delayed
             (configure* options))
   :mode 644))


(declare format-value)

(defn format-map
  [[key value]]
  (cond
   (map? value) (format
                 "%s {\n%s}\n" (name key) (format-value value))
   (or (seq? value)
       (vector? value)) (string/join
                         ""
                         (map #(format-value {key %}) value))
   (= :include key) (format "%s (%s)\n" (name key) (format-value value))
   :else (format "%s = %s\n" (name key) (format-value value))))

(defn format-value
  [value]
  (cond
   (map? value) (string/join
                 ""
                 (map format-map value))
   (string? value) (format "\"%s\"" value)
   :else (format "%s" value)))

(defn metrics
  "Configure metrics"
  [{:as options}]
  (remote-file/remote-file
   "/etc/ganglia/gmond.conf"
   :content (format-value options)
   :mode 644))

(def default-metrics
  {:globals {:daemonize :yes
             :setuid :yes
             :user :ganglia
             :debug_level 0
             :max_udp_msg_len  1472
             :mute :no
             :deaf :no
             :host_dmax  0              ; secs
             :cleanup_threshold  300    ; secs
             :gexec :no
             :send_metadata_interval  0}

   ;; If a cluster attribute is specified, then all gmond hosts are wrapped
   ;; inside of a <CLUSTER> tag.  If you do not specify a cluster tag, then all
   ;; <HOSTS> will NOT be wrapped inside of a <CLUSTER> tag.
   :cluster {:name "unspecified"
             :owner "unspecified"
             :latlong "unspecified"
             :url "unspecified"}

   ;;  The host section describes attributes of the host, like the location
   :host {:location  "unspecified"}

   ;; Feel free to specify as many udp_send_channels as you like.  Gmond used
   ;; to only support having a single channel
   :udp_send_channel {:mcast_join  :239.2.11.71
                      :port  8649
                      :ttl  1}

   ;; You can specify as many udp_recv_channels as you like as well.
   :udp_recv_channel {:mcast_join  :239.2.11.71
                      :port  8649
                      :bind  :239.2.11.71}

   ;; You can specify as many tcp_accept_channels as you like to share
   ;; an xml description of the state of the cluster
   :tcp_accept_channel { :port  8649 }

   ;; Each metrics module that is referenced by gmond must be specified and
   ;; loaded. If the module has been statically linked with gmond, it does not
   ;; require a load path. However all dynamically loadable modules must include
   ;; a load path.
   :modules {:module [{:name  "core_metrics"}
                      {:name  "cpu_module"
                       :path  "/usr/lib/ganglia/modcpu.so"}
                      {:name  "disk_module"
                       :path  "/usr/lib/ganglia/moddisk.so"}
                      {:name  "load_module"
                       :path  "/usr/lib/ganglia/modload.so"}
                      {:name  "mem_module"
                       :path  "/usr/lib/ganglia/modmem.so"}
                      {:name  "net_module"
                       :path  "/usr/lib/ganglia/modnet.so"}
                      {:name  "proc_module"
                       :path  "/usr/lib/ganglia/modproc.so"}
                      {:name  "sys_module"
                       :path  "/usr/lib/ganglia/modsys.so"}]}

   :include "/etc/ganglia/conf.d/*.conf"


   ;; The old internal 2.5.x metric array has been replaced by the following
   ;; collection_group directives.  What follows is the default behavior for
   ;; collecting and sending metrics that is as close to 2.5.x behavior as
   ;; possible.

   ;; This collection group will cause a heartbeat (or beacon) to be sent every
   ;; 20 seconds.  In the heartbeat is the GMOND_STARTED data which expresses
   ;; the age of the running gmond. */
   :collection_group
   [{:collect_once  :yes
     :time_threshold  20
     :metric {:name  "heartbeat"}}

    ;; This collection group will send general info about this host every 1200
    ;; secs. This information doesn't change between reboots and is only
    ;; collected once.
    {:collect_once  :yes
     :time_threshold  1200
     :metric [{:name  "cpu_num"
               :title  "CPU Count"}
              {:name  "cpu_speed"
               :title  "CPU Speed"}
              {:name  "mem_total"
               :title  "Memory Total"}
              ;; Should this be here? Swap can be added/removed
              ;; between reboots.
              {:name  "swap_total"
               :title  "Swap Space Total"}
              {:name  "boottime"
               :title  "Last Boot Time"}
              {:name  "machine_type"
               :title  "Machine Type"}
              {:name  "os_name"
               :title  "Operating System"}
              {:name  "os_release"
               :title  "Operating System Release"}
              {:name  "location"
               :title  "Location"}]}

    ;; This collection group will send the status of gexecd for this host every
    ;; 300 secs. Unlike 2.5.x the default behavior is to report gexecd OFF.
    {:collect_once  :yes
     :time_threshold  300
     :metric {:name  "gexec"
              :title  "Gexec Status"}}

    ;; This collection group will collect the CPU status info every 20 secs.  The
    ;; time threshold is set to 90 seconds.  In honesty, this time_threshold
    ;; could be set significantly higher to reduce unneccessary network
    ;; chatter.
    {
     :collect_every  20
     :time_threshold  90
     ;;  CPU status
     :metric [{:name  "cpu_user"
               :value_threshold  "1.0"
               :title  "CPU User"}
              {:name  "cpu_system"
               :value_threshold  "1.0"
               :title  "CPU System"}
              {:name  "cpu_idle"
               :value_threshold  "5.0"
               :title  "CPU Idle"}
              {:name  "cpu_nice"
               :value_threshold  "1.0"
               :title  "CPU Nice"}
              {:name  "cpu_aidle"
               :value_threshold  "5.0"
               :title  "CPU aidle"}
              {:name  "cpu_wio"
               :value_threshold  "1.0"
               :title  "CPU wio"}

              ;; The next two metrics are optional if you want
              ;; more detail, since they are accounted
              ;; for in cpu_system.

              ;; {:name  "cpu_intr"
              ;;  :value_threshold  "1.0"
              ;;  :title  "CPU intr"}
              ;; {:name  "cpu_sintr"
              ;;  :value_threshold  "1.0"
              ;;  :title  "CPU sintr"}
              ]}

    {:collect_every  20
     :time_threshold  90
     ;; Load Averages
     :metric [{:name  "load_one"
               :value_threshold  "1.0"
               :title  "One Minute Load Average"}
              {:name  "load_five"
               :value_threshold  "1.0"
               :title  "Five Minute Load Average"}
              {:name  "load_fifteen"
               :value_threshold  "1.0"
               :title  "Fifteen Minute Load Average"}]}

    ;; This group collects the number of running and total processes
    {:collect_every  80
     :time_threshold  950
     :metric [{:name  "proc_run"
               :value_threshold  "1.0"
               :title  "Total Running Processes"}
              {:name  "proc_total"
               :value_threshold  "1.0"
               :title  "Total Processes"}]}

    ;; This collection group grabs the volatile memory metrics every 40 secs and
    ;; sends them at least every 180 secs.  This time_threshold can be increased
    ;; significantly to reduce unneeded network traffic.
    {
     :collect_every  40
     :time_threshold  180
     :metric [{:name  "mem_free"
               :value_threshold  "1024.0"
               :title  "Free Memory"}
              {:name  "mem_shared"
               :value_threshold  "1024.0"
               :title  "Shared Memory"}
              {:name  "mem_buffers"
               :value_threshold  "1024.0"
               :title  "Memory Buffers"}
              {:name  "mem_cached"
               :value_threshold  "1024.0"
               :title  "Cached Memory"}
              {:name  "swap_free"
               :value_threshold  "1024.0"
               :title  "Free Swap Space"}]}

    {:collect_every  40
     :time_threshold  300
     :metric [{:name  "bytes_out"
               :value_threshold  4096
               :title  "Bytes Sent"}
              {:name  "bytes_in"
               :value_threshold  4096
               :title  "Bytes Received"}
              {:name  "pkts_in"
               :value_threshold  256
               :title  "Packets Received"}
              {:name  "pkts_out"
               :value_threshold  256
               :title  "Packets Sent"}]}

    ;; Different than 2.5.x default since the old config made no sense
    {:collect_every  1800
     :time_threshold  3600
     :metric {:name  "disk_total"
              :value_threshold  1.0
              :title  "Total Disk Space"}}

    {:collect_every  40
     :time_threshold  180
     :metric [{:name  "disk_free"
               :value_threshold  1.0
               :title  "Disk Space Available"}
              {:name  "part_max_used"
               :value_threshold  1.0
               :title  "Maximum Disk Space Used"}]}]})

(defn nagios-monitor
  "Monitor ganglia web frontent using nagios."
  [& {:keys [url service_description]
      :or {service_description "Ganglia Web Frontend"}
      :as options}]
  (nagios-config/monitor-http
   :url "/ganglia"
   :service_description service_description))

(defn check-ganglia-script
  []
  (remote-file/remote-file
   "/usr/lib/nagios/plugins/check_ganglia.py"
   :template "crate/ganglia/check_ganglia.py"
   :mode "0755")
  (nagios-config/command
   :command_name "check_ganglia"
   :command_line
   "$USER1$/check_ganglia.py -h $HOSTNAME$ -m $ARG1$ -w $ARG2$ -c $ARG3$"))

(defn nagios-monitor-metric
  [metric warn critical
   & {:keys [service_description servicegroups]
      :or {servicegroups [:ganglia-metrics]}}]
  (nagios-config/service
   {:service_description (or service_description (format "%s" metric))
    :servicegroups servicegroups
    :check_command (format "check_ganglia!%s!%s!%s" metric warn critical)}))

#_
(pallet.core/defnode ganglia
  []
  :bootstrap [(pallet.crate.automated-admin-user/automated-admin-user)]
  :configure [(pallet.crate.ganglia/install)
              (pallet.crate.ganglia/configure
               :data_sources {"localhost" {:hosts ["localhost"]}
                              "gm1" {:hosts "gm1"}})
              (pallet.crate.ganglia/monitor)
              (pallet.crate.ganglia/metrics
               pallet.crate.ganglia/default-metrics)]
  :restart-ganglia [(pallet.resource.service/service "gmetad" :action :restart)])

#_
(pallet.core/defnode gm1
  []
  :bootstrap [(pallet.crate.automated-admin-user/automated-admin-user)]
  :configure [(pallet.crate.ganglia/monitor)
              (pallet.crate.ganglia/metrics
               pallet.crate.ganglia/default-metrics)])

#_
(pallet.core/defnode ganglia
  []
  :bootstrap [(pallet.crate.automated-admin-user/automated-admin-user)]
  :configure [(pallet.crate.nagios/nagios "nagiospwd")
              (pallet.crate.ssh/nagios-monitor)
              (pallet.crate.ganglia/install)
              (pallet.crate.ganglia/configure
               :data_sources {"localhost" {:hosts ["localhost"]}
                              "gm1" {:hosts "gm1"}})
              (pallet.crate.ganglia/monitor)
              (pallet.crate.ganglia/metrics
               pallet.crate.ganglia/default-metrics)
              (pallet.crate.ganglia/nagios-monitor)
              (pallet.crate.ganglia/check-ganglia-script)
              (pallet.crate.ganglia/nagios-monitor-metric "cpu_user" 80 90)]
 :restart-ganglia [(pallet.resource.service/service "gmetad" :action :restart)]
 :restart-nagios [(pallet.resource.service/service "nagios3" :action :restart)])
