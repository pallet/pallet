(ns pallet.crate.iptables
  "Crate for managing iptables"
  (:require
   pallet.target
   [pallet.arguments :as arguments]
   [pallet.stevedore :as stevedore]
   pallet.resource
   pallet.resource.remote-file
   pallet.resource.file
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
  [[table rules]]
  (pallet.stevedore/script
   (var tmp @(mktemp iptablesXXXX))
   ~(pallet.resource.remote-file/remote-file*
     "$tmp" :content rules)
   ~(pallet.stevedore/checked-script
     "Restore IPtables"
     ("/sbin/iptables-restore" < @tmp))
   (rm @tmp)))

(defn format-iptables
  [tables]
  (string/join \newline (map second tables)))

(defn iptables*
  "Combine all iptables rules"
  [args]
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
        packager (pallet.target/packager)]
    (condp = packager
        :aptitude (stevedore/do-script* (map restore-iptables tables))
        :yum (pallet.resource.remote-file/remote-file*
              "/etc/sysconfig/iptables"
              :mode "0755"
              :content (format-iptables tables)))))

(pallet.resource/defaggregate iptables-rule
  "Define a rule for the iptables. The argument should be a string containing an
iptables configuration line (cf. arguments to an iptables invocation)"
  iptables* [table config-line])

(defn iptables-accept-established
  "Accept established connections"
  []
  (iptables-rule
   "filter"
   "-A FWR -m state --state RELATED,ESTABLISHED -j ACCEPT"))

(defn iptables-accept-icmp
  "Accept ICMP"
  []
  (iptables-rule "filter" "-A FWR -p icmp -j ACCEPT"))

(defonce accept-option-strings
  {:source " -s %s" :source-range " -src-range %s"})

(defn iptables-accept-port
  "Accept specific port, by default for tcp."
  ([port] (iptables-accept-port port "tcp"))
  ([port protocol & {:keys [source source-range] :as options}]
     (iptables-rule
      "filter"
      (arguments/delayed
       (format
        "-A FWR -p %s%s --dport %s -j ACCEPT"
        protocol
        (reduce
         #(str %1 (format
                   ((first %2) accept-option-strings)
                   (arguments/evaluate (second %2))))
         "" options)
        port)))))

(defn iptables-redirect-port
  "Redirect a specific port, by default for tcp."
  ([from-port to-port] (iptables-redirect-port from-port to-port "tcp"))
  ([from-port to-port protocol]
     (iptables-rule "nat"
      (format "-I PREROUTING -p %s --dport %s -j REDIRECT --to-port %s"
              protocol from-port to-port))))

(defn iptables-throttle
  "Throttle repeated connection attempts.
   http://hostingfu.com/article/ssh-dictionary-attack-prevention-with-iptables"
  ([name port] (iptables-throttle name port "tcp" 60 4))
  ([name port protocol time-period hitcount]
     (iptables-rule "filter"
      (format
       "-N %s
-A FWR -p %s --dport %s -m state --state NEW -j %s
-A %s -m recent --set --name %s
-A %s -m recent --update --seconds %s --hitcount %s --name %s -j DROP"
       name protocol port name name name name time-period hitcount name))))
