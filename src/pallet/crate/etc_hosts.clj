(ns pallet.crate.etc-hosts
  "/etc/hosts file."
 (:require
   [clojure.string :as string]
   [pallet.node :as node]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils])
 (:use
  [pallet.actions :only [exec-checked-script remote-file sed]]
  [pallet.compute :only [os-hierarchy]]
  [pallet.crate
   :only [def-plan-fn defplan get-settings update-settings
          nodes-in-group nodes-with-role
          target-name os-family target-name defmulti-plan defmethod-plan]]
  [pallet.monad :only [let-s]]))

(defn- format-entry
  [entry]
  (format "%s %s"  (key entry) (name (val entry))))

(def-plan-fn host
  "Declare a host entry. Names should be a sting containing one or more names
  for the address"
  [address names]
  (update-settings :hosts merge {address names}))

(def-plan-fn hosts-for-group
  "Declare host entries for all nodes of a group"
  [group-name & {:keys [private-ip]}]
  [ip (m-result (if private-ip node/private-ip node/primary-ip))
   group-nodes (nodes-in-group group-name)]
  (update-settings
   :hosts merge (into {} (map #(vector (ip %) (node/hostname %)) group-nodes))))

(def-plan-fn hosts-for-role
  "Declare host entries for all nodes of a role"
  [role & {:keys [private-ip]}]
  [ip (m-result (if private-ip node/private-ip node/primary-ip))
   nodes (nodes-with-role role)]
  (update-settings
   :hosts merge (into {}
                      (map
                       #(vector (ip %) (node/hostname %))
                       (map :node nodes)))))

(defn ^{:private true} localhost
  ([node-name]
     {"127.0.0.1" (str "localhost localhost.localdomain " node-name)})
  ([]
     {"127.0.0.1" (str "localhost localhost.localdomain")}))

(defn- format-hosts*
  [entries]
  (string/join "\n" (map format-entry entries)))

(defplan format-hosts
  [settings (get-settings :hosts)
   node-name target-name]
  (m-result
   (format-hosts*
    (merge
     settings
     (if (some #(= node-name %) (vals settings))
       (localhost)
       (localhost node-name))))))

(defplan hosts
  "Writes the hosts files"
  [content format-hosts]
  (remote-file
   (stevedore/script (~lib/etc-hosts))
   :owner "root:root"
   :mode 644
   :content content))

;;; set the node's host name.
(require 'pallet.debug)

(defmulti-plan set-hostname*
  (fn [hostname]
    (clojure.tools.logging/debugf "hostname dispatch %s" hostname)
    (let-s
     [_ (pallet.debug/debugf "setting hostname to %s" hostname)
      os os-family
      _ (pallet.debug/debugf "hostname for os %s" os)]
     os))
  :hierarchy #'os-hierarchy)

(defmethod-plan set-hostname* :linux [hostname]
  ;; change the hostname now
  (exec-checked-script
   "Set hostname"
   ("hostname " ~hostname))
  ;; make sure this change will survive reboots
  (remote-file
   "/etc/hostname"
   :owner "root" :group "root" :mode "0644"
   :content hostname))

(defmethod-plan set-hostname* :rh-base [hostname]
  ;; change the hostname now
  (exec-checked-script "Set hostname" ("hostname " ~hostname))
  ;; make sure this change will survive reboots
  (sed "/etc/sysconfig/network"
       {"HOSTNAME=.*" (str "HOSTNAME=" hostname)}))

(def-plan-fn set-hostname
  "Set the hostname on a node. Note that sudo may stop working if the
hostname is not in /etc/hosts."
  []
  [node-name target-name]
  (sed (stevedore/script (~lib/etc-hosts))
       {"127\\.0\\.0\\.1\\(.*\\)" (str "127.0.0.1\\1 " node-name)}
       :restriction (str "/" node-name "/ !")
       :quote-with "'")
  (set-hostname* node-name))
