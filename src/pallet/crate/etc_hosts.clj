(ns pallet.crate.etc-hosts
  "/etc/hosts file."
  (:require
   [clojure.string :as string]
   [clojure.string :refer [blank?]]
   [clojure.tools.logging :refer [debugf]]
   [pallet.actions
    :refer [exec-checked-script exec-script remote-file sed]]
   [pallet.compute :refer [os-hierarchy]]
   [pallet.crate
    :refer [defmethod-plan
            defmulti-plan
            os-family
            target-name
            target-node
            target-node]]
   [pallet.node :as node :refer [primary-ip private-ip]]
   [pallet.plan :refer [defplan]]
   [pallet.script.lib :as lib]
   [pallet.settings :refer [get-settings update-settings]]
   [pallet.stevedore :as stevedore :refer [with-source-line-comments]]
   [pallet.utils :as utils]))

;;; ## Add entries to the host file settings
(defn merge-hosts [& ms]
  (apply merge-with (comp distinct concat) ms))

(defplan add-host
  "Declare a host entry. Names should be a sequence of names."
  [ip-address names]
  (update-settings :hosts merge-hosts
                   {ip-address (if (string? names) [names] names)}))

(defplan add-hosts
  "Add a map of ip address to a sequence of hostname/aliases, to the host file
  settings."
  [hosts-map]
  {:pre [(every? (complement string?) (vals hosts-map))]}
  (update-settings :hosts merge-hosts hosts-map))

;;; ## Query Hostname and DNS
(defplan hostname
  "Get the hostname as reported on the node."
  [{:keys [fqdn]}]
  (let [r (exec-checked-script
           "hostname"
           (hostname ~(if fqdn "-f" "")))]
    (:out r)))

(defplan reverse-dns
  "Get the hostname reported for the specified ip."
  [ip]
  (let [r (exec-checked-script
           (str "reverse DNS for " ip)
           (pipe ("host" ~ip) ("awk" "'{print $NF}'")))]
    (:out r)))

(defplan resolve-dns
  "Get the ip for a hostname."
  [hostname]
  (let [r (exec-checked-script
           (str "Resolve DNS for " hostname)
           (pipe ("host" ~hostname) ("awk" "'{print $NF}'")))]
    (:out r)))


(defplan host-entry
  "Get a host entry for the current node. Options all default to true
  and hostname takes priority over target-name, and private-ip over
  primary-ip."
  [{:keys [use-hostname use-private-ip]
    :or {use-hostname true use-private-ip true}}]
  (let [h (when use-hostname (hostname {:fqdn true}))
        n (target-node)]
    {(or (and use-private-ip (private-ip n))
         (primary-ip n))
     (vec
      (filter identity
              [(if (and use-hostname (not (blank? (:out @h))))
                 (:out @h)
                 (target-name))]))}))


;;; ## Localhost and other Aliases
(def localhost
  {"127.0.0.1" ["localhost" "localhost.localdomain "]})

(defn localhost-hostname
  [& node-names]
  {"127.0.1.1" node-names})

(def ipv6-aliases
  {"::1" ["ip6-localhost" "ip6-loopback"]
   "fe00::0" ["ip6-localnet"]
   "ff00::0" ["ip6-mcastprefix"]
   "ff02::1" ["ip6-allnodes"]
   "ff02::2" ["ip6-allrouters"]
   "ff02::3" ["ip6-allhosts"]})

;;; ## Host File Generation
(defn- format-entry
  [entry]
  (format "%s %s"  (key entry) (string/join " " (map name (val entry)))))

(defn- format-hosts*
  [entries]
  (string/join "\n" (map format-entry (sort-by key entries))))

(defplan format-hosts
  []
  (let [settings (get-settings :hosts)]
    (format-hosts* settings)))

(defplan hosts
  "Writes the hosts files"
  []
  (let [content (format-hosts)]
    (remote-file (stevedore/fragment (~lib/etc-hosts))
     :owner "root"
     :group "root"
     :mode 644
     :content content)))

;;; ## Set hostname
(defmulti-plan set-hostname*
  (fn [hostname]
    (assert hostname "Must specify a valid hostname")
    (debugf "hostname dispatch %s" hostname)
    (let [os (os-family)]
      (debugf "hostname for os %s" os)
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

(defplan set-hostname
  "Set the hostname on a node. Note that sudo may stop working if the
hostname is not in /etc/hosts."
  [& {:keys [update-etc-hosts] :or {update-etc-hosts true}}]
  (let [node-name (target-name)]
    (when update-etc-hosts
      (when-not (:exit (exec-script ("grep" ~node-name (lib/etc-hosts))))
        (exec-checked-script
         "Add self hostname"
         (println ">>" (lib/etc-hosts))
         ((println ~(node/primary-ip (target-node)) " " ~node-name)
          ">>" (lib/etc-hosts)))))
    (set-hostname* node-name)))
