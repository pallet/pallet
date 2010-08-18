(ns pallet.crate.ssh
  "Crate for managing ssh"
  (:require
   pallet.target
   pallet.crate.iptables
   pallet.resource.package
   pallet.resource.service
   [pallet.crate.nagios-config :as nagios-config]))

(defn openssh
  "Install OpenSSH"
  []
  (pallet.resource.package/packages
   :yum ["openssh-clients" "openssh"]
   :aptitude ["openssh-client" "openssh-server"]))

(defn service-name
  "SSH service name"
  ([] (service-name (pallet.target/packager)))
  ([packager]
     (condp = packager
         :aptitude "ssh"
         :yum "sshd")))

(defn sshd-config
  "Take an sshd config string, and write to sshd_conf."
  [config]
  (pallet.resource.remote-file/remote-file
   "/etc/ssh/sshd_config"
   :mode "0644"
   :owner "root"
   :content config)
  (pallet.resource.service/service
   (pallet.arguments/computed
    (fn [] (service-name)))
   :action :reload))


(defn iptables-accept
  "Accept ssh, by default on port 22"
  ([] (iptables-accept 22))
  ([port]
     (pallet.crate.iptables/iptables-accept-port port)))

(defn iptables-throttle
  "Throttle ssh connection attempts, by default on port 22"
  ([] (iptables-throttle 22))
  ([port] (iptables-throttle port 60 4))
  ([port time-period hitcount]
     (pallet.crate.iptables/iptables-throttle
      "SSH_CHECK" port "tcp" time-period hitcount)))

(defn nagios-monitor
  "Configure nagios monitoring for ssh"
  [& {:keys [service-group service-description command]
      :or {service-group "ssh-services"
           command "check_ssh"
           service-description "SSH"}}]
  (nagios-config/service
   {:service-group service-group
    :service-description service-description
    :command command}))
