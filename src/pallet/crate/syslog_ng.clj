(ns pallet.crate.syslog-ng
  "Install and configure syslog-ng"
  (:require
   [pallet.compute :as compute]
   [pallet.parameter :as parameter]
   [pallet.resource :as resource]
   [pallet.target :as target]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.package :as package]
   [pallet.resource.user :as user]
   [pallet.crate.iptables :as iptables]
   [clojure.string :as string]))

(defn default-group "adm")
(defn default-mode "0640")

(def default-options
  {:chain_hostnames 0
   :time_reopen 10
   :time_reap 360
   :sync 0
   :log_fifo_size 2048
   :create_dirs "yes"
   :group "adm"
   :dir_group "adm"
   :perm "0640"
   :dir_perm "0750"
   :use_dns "no"
   :stats_freq 0
   :bad_hostname "^gconfd$"})

(def default-sources
  {:s_all {:internal true
           :unix-stream "/dev/log"
           :file {:value "/proc/kmsg" :log_prefix "kernel: "}}})

(defn receive-from
  "Create a configuration for receiving from remote syslog-ng client. The return
   value is suitable for specifying a source."
  ([protocol] (receive-from protocol 514))
  ([protocol port]  { protocol {:port port}}))

(def default-destinations
  {:df_auth { :file "/var/log/auth.log" }
   :df_syslog { :file "/var/log/syslog" }
   :df_cron { :file "/var/log/cron.log" }
   :df_daemon { :file "/var/log/daemon.log" }
   :df_kern { :file "/var/log/kern.log" }
   :df_lpr { :file "/var/log/lpr.log" }
   :df_mail { :file "/var/log/mail.log" }
   :df_user { :file "/var/log/user.log" }
   :df_uucp { :file "/var/log/uucp.log" }
   :df_facility_dot_info { :file "/var/log/$FACILITY.info" }
   :df_facility_dot_notice { :file "/var/log/$FACILITY.notice" }
   :df_facility_dot_warn { :file "/var/log/$FACILITY.warn" }
   :df_facility_dot_err { :file "/var/log/$FACILITY.err" }
   :df_facility_dot_crit { :file "/var/log/$FACILITY.crit" }
   :df_news_dot_notice { :file {:value "/var/log/news/news.notice"
                                :owner "news"} }
   :df_news_dot_err { :file {:value "/var/log/news/news.err" :owner "news"} }
   :df_news_dot_crit { :file {:value "/var/log/news/news.crit" :owner "news"} }
   :df_debug { :file "/var/log/debug" }
   :df_messages { :file "/var/log/messages" }
   :dp_xconsole { :pipe "/dev/xconsole" }
   :du_all { :usertty "*" }})

(defn send-to
  "Create a configuration for sending to a remote syslog-ng server. The return
   value is suitable for specifying a destination."
  ([destination] (send-to destination "tcp" 514))
  ([destination protocol] (send-to destination protocol 514))
  ([destination protocol port]
     {:tcp {:value destination :port port}}))

(def default-filters
  {:f_auth { :facility "auth, authpriv" }
   :f_syslog { :not { :facility "auth, authpriv" } }
   :f_cron { :facility "cron" }
   :f_daemon { :facility "daemon" }
   :f_kern { :facility "kern" }
   :f_lpr { :facility "lpr" }
   :f_mail { :facility "mail" }
   :f_news { :facility "news" }
   :f_user { :facility "user" }
   :f_uucp { :facility "uucp" }
   :f_at_least_info { :level "info..emerg" }
   :f_at_least_notice { :level "notice..emerg" }
   :f_at_least_warn { :level "warn..emerg" }
   :f_at_least_err { :level "err..emerg" }
   :f_at_least_crit { :level "crit..emerg" }
   :f_debug {:and {:level "debug"
                   :not {:facility "auth, authpriv, news, mail"}}}
   :f_messages {:and {:level "info,notice,warn"
                      :not {:facility "auth,authpriv,cron,daemon,mail,news"}}}
   :f_emerg { :level "emerg" }
   :f_xconsole {:or {:facility "daemon,mail"
                     :level "debug,info,notice,warn"
                     :and {:facility "news"
                           :level "crit,err,notice"}}}})

(def default-logs
  [{:source :s_all
    :filter :f_auth
    :destination :df_auth}
   {:source :s_all
    :filter :f_syslog
    :destination :df_syslog}
   {:source :s_all
    :filter :f_daemon
    :destination :df_daemon}
   {:source :s_all
    :filter :f_kern
    :destination :df_kern}
   {:source :s_all
    :filter :f_lpr
    :destination :df_lpr}
   {:source :s_all
    :filter :f_mail
    :destination :df_mail}
   {:source :s_all
    :filter :f_user
    :destination :df_user}
   {:source :s_all
    :filter :f_uucp
    :destination :df_uucp}
   {:source :s_all
    :filter [:f_mail :f_at_least_info]
    :destination :df_facility_dot_info}
   {:source :s_all
    :filter [:f_mail :f_at_least_warn]
    :destination :df_facility_dot_warn}
   {:source :s_all
    :filter [:f_mail :f_at_least_err]
    :destination :df_facility_dot_err}
   {:source :s_all
    :filter [:f_news :f_at_least_crit]
    :destination :df_news_dot_crit}
   {:source :s_all
    :filter [:f_news :f_at_least_err]
    :destination :df_news_dot_err}
   {:source :s_all
    :filter [:f_news :f_at_least_notice]
    :destination :df_news_dot_notice}
   {:source :s_all
    :filter :f_debug
    :destination :df_debug}
   {:source :s_all
    :filter :f_messages
    :destination :df_messages}
   {:source :s_all
    :filter :f_emerg
    :destination :du_all}
   {:source :s_all
    :filter :f_xconsole
    :destination :dp_xconsole}])

;; :key value -> key(value)
;; :key { :p1 v1 :p2 v2 } -> key(p1(v1) p2(v2))
;; :key { :value v :p1 v1 :p2 v2 } -> key(v p1(v1) p2(v2))

(defmulti property-fmt
  (fn [& [value & options]]
    (cond
     (map? value) :map
     :else :default)))

(defmulti value-fmt
  (fn [value key]
    (cond
     (map? value) :map
     (and (instance? Boolean value) value) :true
     (instance? clojure.lang.Named value) :named
     :else :default)))

(defmethod property-fmt :map
  ([value] (property-fmt value ";\n"))
  ([value seperator]
     (string/join
      ""
      (map
       #(cond
         (#{:not} (first %)) (str (name (first %)) " "
                                  (property-fmt (second %) seperator))
         (#{:and :or} (first %)) (str "("
                                      (string/trim
                                       (string/join
                                        (str " " (name (first %)) " ")
                                        (map (fn [x]
                                               (property-fmt
                                                (apply hash-map x) " "))
                                             (second %))))
                                      ")" seperator)
         (vector? (second %)) (string/join
                               ""
                               (map (fn [x]
                                      (property-fmt { (first %) x} seperator))
                                    (second %)))
         :else (str (name (first %)) "("
                    (string/trim
                     (str
                      (value-fmt (second %) (first %))
                      " "
                      (property-fmt (second %) " ")))
                    ")" seperator))
       (dissoc value :value)))))

(defmethod property-fmt :default
  [value & _]
  nil)

(def quoted-keys
  #{:file :pipe :unix-stream :bad_hostname :template :log_prefix})

(defn format-value [value key]
  (if (quoted-keys key)
    (pr-str value)
    (str value)))

(defmethod value-fmt :default
  [value key]
  (format-value value key))

(defmethod value-fmt :true
  [value key]
  nil)

(defmethod value-fmt :named
  [value key]
  (format-value (name value) key))

(defmethod value-fmt :map
  [value key]
  (format-value (:value value) key))



(defn configure-block
  "Define a syslog-ng configuration block"
  [block-type properties]
  (format
   "%s {\n%s\n};\n"
   block-type
   (string/trim (property-fmt properties))))

(defn config*
  [& {:keys [options sources destinations filters logs]
      :or {options default-options
           sources default-sources
           destinations default-destinations
           filters default-filters
           logs default-logs}
      :as args}]
  (remote-file/remote-file*
   "/etc/syslog-ng/syslog-ng.conf"
   :content (str
             (configure-block "options" options)
             (string/join
              \newline
              (map #(configure-block
                     (str "source " (name (first %))) (second %))
                   sources))
             (string/join
              \newline
              (map #(configure-block
                     (str "destination " (name (first %))) (second %))
                   destinations))
             (string/join
              \newline
              (map #(configure-block
                     (str "filter " (name (first %))) (second %))
                   filters))
             (string/join
              \newline
              (map #(configure-block "log" %) logs)))
   :literal true))

(resource/defresource config
  config* [& options])

(defn install
  "Install from package. keys correspond to sections of the syslog-ng.conf file"
  [& {:keys [options sources destinations filters logs]
      :as args}]
  (package/package "syslog-ng")
  (when-let [group (:group options)]
    (user/group group :system true))
  (when-let [dir_group (:dir_group options)]
    (when (not (= dir_group (:group options)))
      (user/group dir_group :system true)))
  (apply
   config
   (apply concat
          (select-keys args [:options :sources :destinations :filters :logs]))))

(defn set-server-ip
  "Set the syslog-ng server ip, so that it may be picked up by clients. This
   should probably be run in the pre-configure phase."
  []
  (resource/default-parameters
    [:default :syslog-ng :server :ip]
    (fn [_] (compute/primary-ip (target/node)))))

(defn iptables-accept
  "Accept connections, by default on port 514, with tcp."
  ([] (iptables-accept 514 "tcp"))
  ([port] (iptables-accept port "tcp"))
  ([port protocol]
     (pallet.crate.iptables/iptables-accept-port port)))
