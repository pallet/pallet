(ns pallet.crate.sudoers-test
  (:use pallet.crate.sudoers)
  (:use clojure.test
        pallet.test-utils))

(with-private-vars [pallet.crate.sudoers
                    [default-specs
                     param-string
                     write-aliases aliases-for aliases
                     write-defaults defaults-for defaults
                     as-tag tag-or-vector item-or-vector
                     write-cmd-spec write-spec specs
                     sudoer-merge merge-user-spec]]

  (deftest default-specs-test
    (is (instance? clojure.lang.PersistentArrayMap (default-specs {}))))

  (deftest merge-user-spec-test
    (is (= {:a 1 :b 2} (merge-user-spec {:a 1} {:b 2})))
    (is (= [{:a 1} {:b 2}] (merge-user-spec [{:a 1}] [{:b 2}]))))



  (deftest sudoer-merge
    (is (= [{} {} (default-specs {})]
             (sudoer-merge [{} {} (default-specs {})] [])))
    (is (= [{} {} {"user" {:ALL {}}}]
             (sudoer-merge [{} {} {}] [[{} {} {"user" {:ALL {}}}]])))
    (is (= [{} {} {"user" {["/bin/ls"] {} :ALL {:run-as :ALL}}}]
             (sudoer-merge [{} {} {"user" {["/bin/ls"] {}}}]
                           [[{} {} {"user" {:ALL {:run-as :ALL}}}]])))
    (is (= [{} {} (array-map "user1" {} "user2" {})]
             (sudoer-merge [{} {} (array-map "user1" {})]
                           [[{} {} (array-map "user2" {})]])))
    (is (= [{} {} (array-map "user2" {} "user1" {} "user3" {} "user4" {} "user5" {} "user6" {} "user7" {} "user8" {} "user0" {})]
             (sudoer-merge [{} {} (array-map "user2" {})]
                           [[{} {} (array-map "user1" {})]
                            [{} {} (array-map "user3" {})]
                            [{} {} (array-map "user4" {})]
                            [{} {} (array-map "user5" {})]
                            [{} {} (array-map "user6" {})]
                            [{} {} (array-map "user7" {})]
                            [{} {} (array-map "user8" {})]
                            [{} {} (array-map "user0" {})]]))))

  (deftest merge-test
    (let [request
          (-> {:phase :configure :target-id :id}
              (sudoers {} {} (array-map "user1" [{:host "h1" :ALL {}}]))
              (sudoers {} {} (array-map "user2" {:host "h2" :ALL {}})))]
      (is (= [{} {} (array-map "root" {:ALL {:run-as-user :ALL}}
                               "%adm" {:ALL {:run-as-user :ALL}}
                               "user1" [{:host "h1" :ALL {}}]
                               "user2" {:host "h2" :ALL {}})]
               (sudoer-merge
                [{} {} (default-specs {})]
                (->> request :invocations
                     :configure
                     :id
                     :aggregated
                     (map :args)))))))

  (deftest test-param-string
    (is (= "fqdn" (param-string [:fqdn true])))
    (is (= "!fqdn" (param-string [:fqdn false])))
    (is (= "ignore_dot" (param-string [:ignore-dot true])))
    (is (= "passwd_tries=1" (param-string [:passwd-tries 1])))
    (is (= "log_file_1=/var/log/sudo.log"
           (param-string [:log-file-1 "/var/log/sudo.log"])))
    (is (= "mailsub_1=\"mail subject\""
           (param-string [:mailsub-1 "\"mail subject\""])))
    (is (= "syslog_1=auth_1" (param-string [:syslog-1 :auth-1]))))

  (deftest test-write-defaults
    (is (= "Defaults fqdn,!insult,passwd_tries=1\n"
           (write-defaults "" ""
                           {:fqdn true :insult false :passwd-tries 1})))
    (is (= "Defaults>root fqdn,!insult,passwd_tries=1\n"
           (write-defaults ">" "root"
                           {:fqdn true :insult false :passwd-tries 1}))))

  (deftest test-write-aliases
    (is (= "User_Alias ADMINS = user1,user2\n"
           (write-aliases "User_Alias" "ADMINS" [ "user1" "user2" ] ))))

  (defonce test-alias-values {:user { :ADMINS [ "user1" "user2" ] }
                              :host { :TRUSTED [ "host1" ] }
                              :run-as-user { :OP [ "root" "sysop" ] }
                              :cmnd { :KILL [ "kill" ]
                                     :SHELLS [ "/usr/bin/sh" "/usr/bin/csh" "/usr/bin/ksh"]}})

  (defonce test-default-values {:default { :fqdn true }
                                :host { "host" { :lecture false } }
                                :user { "user" { :lecture false } }
                                :run-as-user { "sysop" { :lecture false } } })


  (deftest test-defaults-for
    (is (= "Defaults@host !lecture\n"
           (defaults-for test-default-values :host \@))))

  (deftest test-aliases-for
    (is (= "Host_Alias TRUSTED = host1\n"
           (aliases-for test-alias-values :host "Host_Alias"))))

  (deftest test-defaults
    (is (= "Defaults fqdn
Defaults>sysop !lecture
Defaults:user !lecture
Defaults@host !lecture
"
           (defaults test-default-values))))

  (deftest test-aliases
    (is (= "User_Alias ADMINS = user1,user2
Runas_Alias OP = root,sysop
Host_Alias TRUSTED = host1
Cmnd_Alias KILL = kill
Cmnd_Alias SHELLS = /usr/bin/sh,/usr/bin/csh,/usr/bin/ksh
"
           (aliases test-alias-values))))

  (deftest test-as-tag
    (is (= "PASSWD:") (as-tag :PASSWD)))

  (deftest test-tag-or-vector
    (is (= "PASSWD:" (tag-or-vector :PASSWD)))
    (is (= "PASSWD: NOEXEC:" (tag-or-vector [:PASSWD :NOEXEC]))))

  (deftest test-item-or-vector
    (is (= "fred" (item-or-vector :fred)))
    (is (= "fred" (item-or-vector "fred")))
    (is (= "fred,blogs" (item-or-vector ["fred" :blogs]))))

  (deftest test-write-cmd-spec
    (is (= "/usr/bin/ls"
           (write-cmd-spec ["/usr/bin/ls" {}])))
    (is (= "/usr/bin/ls,/usr/bin/less"
           (write-cmd-spec [["/usr/bin/ls" "/usr/bin/less"] {}])))
    (is (= "(root) /usr/bin/ls"
           (write-cmd-spec ["/usr/bin/ls" {:run-as-user "root"}])))
    (is (= "(OPS) /usr/bin/ls"
           (write-cmd-spec ["/usr/bin/ls" {:run-as-user :OPS}])))
    (is (= "(root,OPS) /usr/bin/ls"
           (write-cmd-spec ["/usr/bin/ls" {:run-as-user ["root" :OPS]}])))
    (is (= "PASSWD: /usr/bin/ls"
           (write-cmd-spec ["/usr/bin/ls" {:tags :PASSWD}])))
    (is (= "(root,OPS) PASSWD: /usr/bin/ls"
           (write-cmd-spec ["/usr/bin/ls" {:run-as-user ["root" :OPS] :tags :PASSWD}]))))

  (deftest test-write-spec
    (is (= "root,OPS ALL = /usr/bin/ls,/usr/bin/less,(root,OPS) PASSWD: /usr/bin/ls"
           (write-spec [["root",:OPS] {["/usr/bin/ls" "/usr/bin/less"] {}
                                       "/usr/bin/ls" {:run-as-user ["root" :OPS] :tags :PASSWD}}]))))

  (deftest specs-test-simple
    (is (= "root ALL = (ALL) ALL
%adm ALL = (ALL) ALL"
           (specs (array-map
                   "root" {:ALL {:run-as-user :ALL}}
                   "%adm" {:ALL {:run-as-user :ALL}})))))

  (deftest specs-test-with-array
    (is (= "root ALL = (ALL) ALL
%adm ALL = (ALL) ALL
bob SPARC = (OP) ALL : SGI = (OP) ALL
jim +biglab = ALL"
           (specs (array-map
                   "root" {:ALL {:run-as-user :ALL}}
                   "%adm" {:ALL {:run-as-user :ALL}}
                   "bob" [{:host :SPARC :ALL {:run-as-user :OP} }
                          {:host :SGI :ALL {:run-as-user :OP} }]
                   "jim"  {:host "+biglab" :ALL {}})))))

  (deftest test-specs
    (is (= "root ALL = (ALL) ALL
%adm ALL = (ALL) ALL
FULLTIMERS ALL = NOPASSWD: ALL
PARTTIMERS ALL = ALL
jack CSNETS = ALL
lisa CUNETS = ALL
operator ALL = DUMPS,KILL,SHUTDOWN,HALT,REBOOT,PRINTING,sudoedit /etc/printcap,/usr/oper/bin/
joe ALL = /usr/bin/su operator
pete HPPA = /usr/bin/passwd [A-z]*,!/usr/bin/passwd root
bob SPARC = (OP) ALL : SGI = (OP) ALL
jim +biglab = ALL
+secretaries ALL = PRINTING,/usr/bin/adduser,/usr/bin/rmuser
fred ALL = (DB) NOPASSWD: ALL
john ALPHA = /usr/bin/su [!-]*,!/usr/bin/su *root*
jen ALL,!SERVERS = ALL
jill SERVERS = /usr/bin/,!SU,!SHELLS
steve CSNETS = (operator) /usr/local/op_commands/
matt valkyrie = KILL
WEBMASTERS www = (www) ALL,(root) /usr/bin/su www
ALL CDROM = NOPASSWD: /sbin/umount /CDROM,/sbin/mount -o nosuid\\,nodev /dev/cd0a /CDROM"
           (specs (array-map
                   "root" {:ALL {:run-as-user :ALL}}
                   "%adm" {:ALL {:run-as-user :ALL}}
                   :FULLTIMERS {:ALL {:tags :NOPASSWD}}
                   :PARTTIMERS {:ALL {}}
                   "jack" { :host :CSNETS :ALL {}}
                   "lisa" { :host :CUNETS :ALL {}}
                   "operator" {[:DUMPS :KILL :SHUTDOWN :HALT :REBOOT :PRINTING "sudoedit /etc/printcap" "/usr/oper/bin/"] {}}
                   "joe"  {["/usr/bin/su operator"] {}}
                   "pete" {:host :HPPA  ["/usr/bin/passwd [A-z]*" "!/usr/bin/passwd root"] {}}
                   "bob" [{:host :SPARC :ALL {:run-as-user :OP} }
                          {:host :SGI :ALL {:run-as-user :OP} }]
                   "jim"  {:host "+biglab" :ALL {}}
                   "+secretaries" { [:PRINTING "/usr/bin/adduser" "/usr/bin/rmuser"] {}}
                   "fred" { :ALL { :run-as-user :DB :tags :NOPASSWD}}
                   "john" { :host :ALPHA ["/usr/bin/su [!-]*" "!/usr/bin/su *root*"] {}}
                   "jen" { :host [:ALL "!SERVERS"] :ALL {}}
                   "jill" {:host :SERVERS ["/usr/bin/" "!SU" "!SHELLS"] {}}
                   "steve" {:host :CSNETS  "/usr/local/op_commands/" {:run-as-user "operator"}}
                   "matt" {:host :valkyrie :KILL {}}
                   :WEBMASTERS {:host :www  :ALL {:run-as-user :www}  "/usr/bin/su www" {:run-as-user :root}}
                   :ALL {:host :CDROM  [ "/sbin/umount /CDROM" "/sbin/mount -o nosuid\\,nodev /dev/cd0a /CDROM"] {:tags :NOPASSWD}}
                   ))))))


(deftest test-man-page-example
  (is (=
       "file=/etc/sudoers
cat > ${file} <<EOF
User_Alias FULLTIMERS = millert,mikef,dowdy
User_Alias PARTTIMERS = bostley,jwfox,crawl
User_Alias WEBMASTERS = will,wendy,wim
Runas_Alias OP = root,operator
Runas_Alias DB = oracle,sybase
Host_Alias SPARC = bigtime,eclipse,moet,anchor
Host_Alias SGI = grolsch,dandelion,black
Host_Alias ALPHA = widget,thalamus,foobar
Host_Alias HPPA = boa,nag,python
Host_Alias CUNETS = 128.138.0.0/255.255.0.0
Host_Alias CSNETS = 128.138.243.0,128.138.204.0/24,128.138.242.0
Host_Alias SERVERS = master,mail,www,ns
Host_Alias CDROM = orion,perseus,hercules
Cmnd_Alias DUMPS = /usr/bin/mt,/usr/sbin/dump,/usr/sbin/rdump,/usr/sbin/restore,/usr/sbin/rrestore
Cmnd_Alias KILL = /usr/bin/kill
Cmnd_Alias PRINTING = /usr/sbin/lpc,/usr/bin/lprm
Cmnd_Alias SHUTDOWN = /usr/sbin/shutdown
Cmnd_Alias HALT = /usr/sbin/halt
Cmnd_Alias REBOOT = /usr/sbin/reboot
Cmnd_Alias SHELLS = /usr/bin/sh,/usr/bin/csh,/usr/bin/ksh,/usr/local/bin/tcsh,/usr/bin/rsh,/usr/local/bin/zsh
Cmnd_Alias SU = /usr/bin/su
Defaults syslog=auth
Defaults>root !set_logname
Defaults:FULLTIMERS !lecture
Defaults:millert !authenticate
Defaults@SERVERS log_year,logfile=/var/log/sudo.log
root ALL = (ALL) ALL
%adm ALL = (ALL) ALL
FULLTIMERS ALL = NOPASSWD: ALL
PARTTIMERS ALL = ALL
jack CSNETS = ALL
lisa CUNETS = ALL
operator ALL = DUMPS,KILL,SHUTDOWN,HALT,REBOOT,PRINTING,sudoedit /etc/printcap,/usr/oper/bin/
joe ALL = /usr/bin/su operator
pete HPPA = /usr/bin/passwd [A-z]*,!/usr/bin/passwd root
bob SPARC = (OP) ALL : SGI = (OP) ALL
jim +biglab = ALL
+secretaries ALL = PRINTING,/usr/bin/adduser,/usr/bin/rmuser
fred ALL = (DB) NOPASSWD: ALL
john ALPHA = /usr/bin/su [!-]*,!/usr/bin/su *root*
jen ALL,!SERVERS = ALL
jill SERVERS = /usr/bin/,!SU,!SHELLS
steve CSNETS = (operator) /usr/local/op_commands/
matt valkyrie = KILL
WEBMASTERS www = (www) ALL,(root) /usr/bin/su www
ALL CDROM = NOPASSWD: /sbin/umount /CDROM,/sbin/mount -o nosuid\\,nodev /dev/cd0a /CDROM
EOF
chmod 0440 ${file}
chown root ${file}
"
       (first
        (build-resources
         []
         (sudoers {:user {:FULLTIMERS ["millert" "mikef" "dowdy"]
                          :PARTTIMERS ["bostley" "jwfox" "crawl"]
                          :WEBMASTERS ["will" "wendy" "wim"]}
                   :host {:SPARC ["bigtime" "eclipse" "moet" "anchor"]
                          :SGI ["grolsch" "dandelion" "black"]
                          :ALPHA  ["widget" "thalamus" "foobar"]
                          :HPPA  ["boa" "nag" "python"]
                          :CUNETS  ["128.138.0.0/255.255.0.0"]
                          :CSNETS  ["128.138.243.0" "128.138.204.0/24" "128.138.242.0"]
                          :SERVERS  ["master" "mail" "www" "ns"]
                          :CDROM  ["orion" "perseus" "hercules"]}
                   :run-as-user {:OP ["root" "operator"]
                                 :DB ["oracle" "sybase"]}
                   :cmnd {:DUMPS ["/usr/bin/mt" "/usr/sbin/dump" "/usr/sbin/rdump" "/usr/sbin/restore" "/usr/sbin/rrestore"]
                          :KILL  ["/usr/bin/kill"]
                          :PRINTING  ["/usr/sbin/lpc" "/usr/bin/lprm"]
                          :SHUTDOWN  ["/usr/sbin/shutdown"]
                          :HALT  ["/usr/sbin/halt"]
                          :REBOOT  ["/usr/sbin/reboot"]
                          :SHELLS  ["/usr/bin/sh" "/usr/bin/csh" "/usr/bin/ksh" "/usr/local/bin/tcsh" "/usr/bin/rsh" "/usr/local/bin/zsh"]
                          :SU  ["/usr/bin/su"] }}
                  {:default { :syslog :auth }
                   :user {:FULLTIMERS { :lecture false }
                          "millert" {:authenticate false}}
                   :host { :SERVERS { :log_year true :logfile "/var/log/sudo.log" } }
                   :run-as-user { "root" { :set_logname false } } }
                  (array-map
                   "root" {:ALL {:run-as-user :ALL}}
                   "%adm" {:ALL {:run-as-user :ALL}}
                   :FULLTIMERS {:ALL {:tags :NOPASSWD}}
                   :PARTTIMERS {:ALL {}}
                   "jack" { :host :CSNETS :ALL {}}
                   "lisa" { :host :CUNETS :ALL {}}
                   "operator" {[:DUMPS :KILL :SHUTDOWN :HALT :REBOOT :PRINTING "sudoedit /etc/printcap" "/usr/oper/bin/"] {}}
                   "joe"  {["/usr/bin/su operator"] {}}
                   "pete" {:host :HPPA  ["/usr/bin/passwd [A-z]*" "!/usr/bin/passwd root"] {}}
                   "bob" [{:host :SPARC :ALL {:run-as-user :OP} }
                          {:host :SGI :ALL {:run-as-user :OP} }]
                   "jim"  {:host "+biglab" :ALL {}}
                   "+secretaries" { [:PRINTING "/usr/bin/adduser" "/usr/bin/rmuser"] {}}
                   "fred" { :ALL { :run-as-user :DB :tags :NOPASSWD}}
                   "john" { :host :ALPHA ["/usr/bin/su [!-]*" "!/usr/bin/su *root*"] {}}
                   "jen" { :host [:ALL "!SERVERS"] :ALL {}}
                   "jill" {:host :SERVERS ["/usr/bin/" "!SU" "!SHELLS"] {}}
                   "steve" {:host :CSNETS  "/usr/local/op_commands/" {:run-as-user "operator"}}
                   "matt" {:host :valkyrie :KILL {}}
                   :WEBMASTERS {:host :www  :ALL {:run-as-user :www}  "/usr/bin/su www" {:run-as-user :root}}
                   :ALL {:host :CDROM  [ "/sbin/umount /CDROM" "/sbin/mount -o nosuid\\,nodev /dev/cd0a /CDROM"] {:tags :NOPASSWD}}
                   )))))))
