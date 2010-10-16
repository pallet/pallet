(ns pallet.crate.iptables
  "Crate for managing iptables"
  (:require
   pallet.target
   [pallet.argument :as argument]
   [pallet.stevedore :as stevedore]
   pallet.resource
   pallet.resource.remote-file
   pallet.resource.file
   [pallet.target :as target]
   [clojure.contrib.string :as string]
   [clojure.contrib.logging :as logging]))

(def prefix
     {"filter" ":INPUT ACCEPT
:FORWARD ACCEPT
:OUTPUT ACCEPT
:FWR -
-A INPUT -j FWR
-A FWR -i lo -j ACCEPT"})
(def suffix
     {"filter" "# Rejects all remaining connections with port-unreachable errors.
-A FWR -p tcp -m tcp --tcp-flags SYN,RST,ACK SYN -j REJECT --reject-with icmp-port-unreachable
-A FWR -p udp -j REJECT --reject-with icmp-port-unreachable
COMMIT
"})

(defn restore-iptables
  [request [table rules]]
  (pallet.stevedore/script
   (var tmp @(mktemp iptablesXXXX))
   ~(pallet.resource.remote-file/remote-file*
     request
     "$tmp" :content rules)
   ~(pallet.stevedore/checked-script
     "Restore IPtables"
     ("/sbin/iptables-restore" < @tmp))
   (rm @tmp)))

(defn format-iptables
  [tables]
  (string/join \newline (map second tables)))


(pallet.resource/defaggregate iptables-rule
  "Define a rule for the iptables. The argument should be a string containing an
iptables configuration line (cf. arguments to an iptables invocation)"
  {:use-arglist [request table config-line]}
  (iptables*
   [request args]
   (let [args (group-by first args)
         tables (into
                 {}
                 (map
                  #(vector
                    (first %)
                    (str
                     "*" (first %) \newline
                     (string/join
                      \newline (filter
                                identity
                                [(prefix (first %))
                                 (string/join
                                  \newline
                                  (map second (second %)))
                                 (suffix (first %) "COMMIT\n")])))) args))
         packager (:target-packager request)]
     (case packager
       :aptitude (stevedore/do-script*
                  (map #(restore-iptables request %) tables))
       :yum (pallet.resource.remote-file/remote-file*
             request
             "/etc/sysconfig/iptables"
             :mode "0755"
             :content (format-iptables tables))))))

(defn iptables-accept-established
  "Accept established connections"
  [request]
  (iptables-rule
   request "filter" "-A FWR -m state --state RELATED,ESTABLISHED -j ACCEPT"))

(defn iptables-accept-icmp
  "Accept ICMP"
  [request]
  (iptables-rule request "filter" "-A FWR -p icmp -j ACCEPT"))

(defonce accept-option-strings
  {:source " -s %s" :source-range " -src-range %s"})

(defn iptables-accept-port
  "Accept specific port, by default for tcp."
  ([request port] (iptables-accept-port request port "tcp"))
  ([request port protocol & {:keys [source source-range] :as options}]
     (iptables-rule
      request "filter"
      (format
       "-A FWR -p %s%s --dport %s -j ACCEPT"
       protocol
       (reduce
        #(str %1 (format
                  ((first %2) accept-option-strings)
                  (second %2)))
        "" options)
       port))))

(defn iptables-redirect-port
  "Redirect a specific port, by default for tcp."
  ([request from-port to-port]
     (iptables-redirect-port request from-port to-port "tcp"))
  ([request from-port to-port protocol]
     (iptables-rule
      request "nat"
      (format "-I PREROUTING -p %s --dport %s -j REDIRECT --to-port %s"
              protocol from-port to-port))))

(defn iptables-throttle
  "Throttle repeated connection attempts.
   http://hostingfu.com/article/ssh-dictionary-attack-prevention-with-iptables"
  ([request name port] (iptables-throttle request name port "tcp" 60 4))
  ([request name port protocol time-period hitcount]
     (iptables-rule
      request "filter"
      (format
       "-N %s
-A FWR -p %s --dport %s -m state --state NEW -j %s
-A %s -m recent --set --name %s
-A %s -m recent --update --seconds %s --hitcount %s --name %s -j DROP"
       name protocol port name name name name time-period hitcount name))))
