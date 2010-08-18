(ns pallet.crate.nagios-config
  (:require
   [pallet.resource :as resource]
   [pallet.arguments :as arguments]
   [pallet.resource.file :as file]
   [pallet.resource.package :as package]
   [pallet.crate.iptables :as iptables]
   [pallet.target :as target]
   [pallet.parameter :as parameter]))

(defn service*
  "A nagios service definition"
  [options]
  (parameter/update-default!
   [:default :nagios :host-services (keyword (.getName (target/node)))]
   (fn [x]
     (distinct
      (conj
       (or x [])
       (select-keys
        options
        [:service-group :service-description :command])))))
  "") ; produces no output on target

(resource/defresource service
  "Configure nagios service monitoring.
     :service-group        name for service group(s) service should be part of
     :command              command for service
     :service-description  description for the service"
  service* [options])

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
   {:service-group "machine"
    :command "check_nrpe_1arg!check_load"
    :service-description  "Current Load"}))

(defn nrpe-check-users
  []
  (service
   {:service-group "machine"
    :command "check_nrpe_1arg!check_users"
    :service-description  "Current Users"}))

(defn nrpe-check-disk
  []
  (service
   {:service-group "machine"
    :command "check_nrpe_1arg!check_hda1"
    :service-description  "Root Disk"}))

(defn nrpe-check-total-procs
  []
  (service
   {:service-group "machine"
    :command "check_nrpe_1arg!check_total_procs"
    :service-description  "Total Processes"}))

(defn nrpe-check-zombie-procs
  []
  (service
   {:service-group "machine"
    :command "check_nrpe_1arg!check_zombie_procs"
    :service-description  "Zombie Processes"}))
