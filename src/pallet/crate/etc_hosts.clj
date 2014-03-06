(ns pallet.crate.etc-hosts
  "/etc/hosts file."
  (:require
   [clojure.string :as string]
   [clojure.string :refer [blank?]]
   [clojure.tools.logging :refer [debugf]]
   [pallet.actions
    :refer [exec-checked-script exec-script remote-file sed]]
   [pallet.compute :refer [os-hierarchy]]
   [pallet.plan :refer [defmethod-plan defmulti-plan defplan]]
   [pallet.script.lib :as lib]
   [pallet.session :refer [target]]
   [pallet.settings :refer [get-settings update-settings]]
   [pallet.stevedore :as stevedore :refer [with-source-line-comments]]
   [pallet.target :as target]
   [pallet.utils :as utils]))

;;; ## Add entries to the host file settings
(defn merge-hosts [& ms]
  (apply merge-with (comp distinct concat) ms))

(defplan add-host
  "Declare a host entry. Names should be a sequence of names."
  [session ip-address names]
  (update-settings
   session
   :hosts merge-hosts
   {ip-address (if (string? names) [names] names)}))

(defplan add-hosts
  "Add a map of ip address to a sequence of hostname/aliases, to the host file
  settings."
  [session hosts-map]
  {:pre [(every? (complement string?) (vals hosts-map))]}
  (update-settings session :hosts merge-hosts hosts-map))

;;; ## Query Hostname and DNS
(defplan hostname
  "Get the hostname as reported on the node."
  [session {:keys [fqdn]}]
  (let [r (exec-checked-script
           session
           "hostname"
           (hostname ~(if fqdn "-f" "")))]
    (:out r)))

(defplan reverse-dns
  "Get the hostname reported for the specified ip."
  [session ip]
  (let [r (exec-checked-script
           session
           (str "reverse DNS for " ip)
           (pipe ("host" ~ip) ("awk" "'{print $NF}'")))]
    (:out r)))

(defplan resolve-dns
  "Get the ip for a hostname."
  [session hostname]
  (let [r (exec-checked-script
           session
           (str "Resolve DNS for " hostname)
           (pipe ("host" ~hostname) ("awk" "'{print $NF}'")))]
    (:out r)))


(defplan host-entry
  "Get a host entry for the current node. Options all default to true
  and hostname takes priority over target-name, and private-ip over
  primary-ip."
  [session
   {:keys [use-hostname use-private-ip]
    :or {use-hostname true use-private-ip true}}]
  (let [h (when use-hostname (hostname {:fqdn true}))
        n (target session)]
    {(or (and use-private-ip (target/private-ip n))
         (target/primary-ip n))
     (vec
      (filter identity
              [(if (and use-hostname (not (blank? (:out @h))))
                 (:out @h)
                 (target/hostname session))]))}))


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
  [session]
  (let [settings (get-settings session :hosts)]
    (format-hosts* settings)))

(defplan hosts
  "Writes the hosts files"
  [session]
  (let [content (format-hosts session)]
    (remote-file
     session
     (stevedore/fragment (~lib/etc-hosts))
     {:owner "root"
      :group "root"
      :mode 644
      :content content})))

;;; ## Set hostname
(defmulti-plan set-hostname*
  (fn [session hostname]
    (assert hostname "Must specify a valid hostname")
    (debugf "hostname dispatch %s" hostname)
    (let [os (target/os-family session)]
      (debugf "hostname for os %s" os)
      os))
  {:hierarchy os-hierarchy})

(defmethod-plan set-hostname* :linux
  [session hostname]
  ;; change the hostname now
  (exec-checked-script
   session
   "Set hostname"
   ("hostname " ~hostname))
  ;; make sure this change will survive reboots
  (remote-file
   session
   "/etc/hostname"
   {:owner "root" :group "root" :mode "0644"
    :content hostname}))

(defmethod-plan set-hostname* :rh-base
  [session hostname]
  ;; change the hostname now
  (exec-checked-script session "Set hostname" ("hostname " ~hostname))
  ;; make sure this change will survive reboots
  (sed session "/etc/sysconfig/network"
       {"HOSTNAME=.*" (str "HOSTNAME=" hostname)}))

(defplan set-hostname
  "Set the hostname on a node. Note that sudo may stop working if the
hostname is not in /etc/hosts."
  [session & {:keys [update-etc-hosts] :or {update-etc-hosts true}}]
  (let [node-name (target/hostname (target session))]
    (when update-etc-hosts
      (when-not (:exit
                 (exec-script session ("grep" ~node-name (lib/etc-hosts))))
        (exec-checked-script
         session
         "Add self hostname"
         (println ">>" (lib/etc-hosts))
         ((println ~(target/primary-ip (target session)) " " ~node-name)
          ">>" (lib/etc-hosts)))))
    (set-hostname* session node-name)))
