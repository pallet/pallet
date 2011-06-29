(ns pallet.execute
  "Exectute commands.  At the moment the only available transport is ssh."
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.action.file :as file]
   [pallet.common.filesystem :as filesystem]
   [pallet.common.shell :as shell]
   [pallet.compute :as compute]
   [pallet.compute.jvm :as jvm]
   [pallet.environment :as environment]
   [pallet.script :as script]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.stevedore.bash :as bash]
   [pallet.utils :as utils]
   [clj-ssh.ssh :as ssh]
   [clojure.string :as string]
   [clojure.contrib.condition :as condition]
   [clojure.java.io :as io]
   [pallet.shell :as ccshell]
   [clojure.tools.logging :as logging]))

(def prolog
  (str "#!/usr/bin/env bash\n" bash/hashlib))

(defn- normalise-eol
  "Convert eol into platform specific value"
  [#^String s]
  (string/replace s #"[\r\n]+" (str \newline)))

(defn- strip-sudo-password
  "Elides the user's password or sudo-password from the given ssh output."
  [#^String s user]
  (string/replace
   s (format "\"%s\"" (or (:password user) (:sudo-password user))) "XXXXXXX"))

(script/defscript sudo-no-password [])
(script/defimpl sudo-no-password :default []
  ("/usr/bin/sudo" -n))
(script/defimpl sudo-no-password
  [#{:centos-5.3 :os-x :darwin :debian :fedora}]
  []
  ("/usr/bin/sudo"))

(defn sudo-cmd-for
  "Construct a sudo command prefix for the specified user."
  [user]
  (if (or (= (:username user) "root") (:no-sudo user))
    "/bin/bash "
    (if-let [pw (:sudo-password user)]
      (str "echo \"" (or (:password user) pw) "\" | /usr/bin/sudo -S ")
      (str (stevedore/script (~sudo-no-password)) " "))))

;;;
(def
  ^{:doc "Specifies the buffer size used to read the ssh output stream.
    Defaults to 10K, to match clj-ssh.ssh/*piped-stream-buffer-size*"}
  ssh-output-buffer-size (atom (* 1024 10)))

(def
  ^{:doc "Specifies the polling period for retrieving ssh command output.
    Defaults to 1000ms."}
  output-poll-period (atom 1000))


;;; local script execution
(defn local-cmds
  "Run local cmds on a target."
  [#^String commands]
  (let [execute (fn [cmd] ((second cmd)))
        rv (doall (map execute (filter #(= :origin (first %)) commands)))]
    rv))

(defn read-buffer [stream]
  (let [buffer-size @ssh-output-buffer-size
        bytes (byte-array buffer-size)
        sb (StringBuilder.)]
    {:sb sb
     :reader (fn []
               (when (pos? (.available stream))
                 (let [num-read (.read stream bytes 0 buffer-size)
                       s (normalise-eol (String. bytes 0 num-read "UTF-8"))]
                   (logging/infof "Output:\n%s" s)
                   (.append sb s)
                   s)))}))

(defn sh-script
  "Run a script on local machine."
  [command]
  (logging/tracef "sh-script %s" command)
  (let [tmp (java.io.File/createTempFile "pallet" "script")]
    (try
      (io/copy (str prolog command) tmp)
      (ccshell/sh "chmod" "+x" (.getPath tmp))
      (let [{:keys [out err proc]} (ccshell/sh
                                    "bash" (.getPath tmp) :async true)
            out-reader (read-buffer out)
            err-reader (read-buffer err)
            period @output-poll-period]
        (with-open [out out err err]
          (while (not (try (.exitValue proc)
                           (catch IllegalThreadStateException _)))
            (Thread/sleep period)
            (logging/spy ((:reader out-reader)))
            (logging/spy ((:reader err-reader))))

          (while (logging/spy ((:reader out-reader))))
          (while (logging/spy ((:reader err-reader))))
          (let [exit (.exitValue proc)]
            (when-not (zero? exit)
              (logging/errorf
               "Command failed: %s\n%s"
               command (str (:sb err-reader))))
            {:exit exit
             :out (str (:sb out-reader))
             :err (str (:sb err-reader))})))
      (finally  (.delete tmp)))))

(defmacro local-script
  "Run a script on the local machine, setting up stevedore to produce the
   correct target specific code"
  [& body]
  `(script/with-script-context
     [(jvm/os-family)]
     (stevedore/with-script-language :pallet.stevedore.bash/bash
       (sh-script
        (stevedore/script
         ~@body)))))

(defn verify-sh-return
  "Verify the return code of a sh execution"
  [msg cmd result]
  (if (zero? (:exit result))
    result
    (assoc result
      :error {:message (format
                        "Error executing script %s\n :cmd %s :out %s\n :err %s"
                        msg cmd (:out result) (:err result))
              :type :pallet-script-excution-error
              :script-exit (:exit result)
              :script-out  (:out result)
              :script-err (:err result)
              :server "localhost"})))

(defmacro local-checked-script
  "Run a script on the local machine, setting up stevedore to produce the
   correct target specific code.  The return code is checked."
  [msg & body]
  `(script/with-template
     [(jvm/os-family)]
     (let [cmd# (stevedore/checked-script ~msg ~@body)]
       (verify-sh-return ~msg cmd# (sh-script cmd#)))))

(defn local-sh-cmds
  "Execute cmds for the session.
   Runs locally as the current user, so useful for testing."
  [{:keys [root-path] :or {root-path "/tmp/"} :as session}]
  (if (seq (action-plan/get-for-target session))
    (letfn [(execute-bash
             [cmdstring]
             (logging/infof "Cmd %s" cmdstring)
             (sh-script cmdstring))
            (transfer
             [transfers]
             (logging/infof "Local transfer")
             (doseq [[from to] transfers]
               (logging/infof "Copying %s to %s" from to)
               (io/copy (io/file from) (io/file to))))]
      (action-plan/execute-for-target
       session
       {:script/bash execute-bash
        :fn/clojure (fn [& _])
        :transfer/to-local transfer
        :transfer/from-local transfer}))
    [nil session]))

;;; ssh

(defonce default-agent-atom (atom nil))
(defn default-agent
  []
  (or @default-agent-atom
      (swap! default-agent-atom
             (fn [agent]
               (if agent
                 agent
                 (ssh/create-ssh-agent false))))))

(defn possibly-add-identity
  [agent private-key-path passphrase]
  (if passphrase
    (ssh/add-identity agent private-key-path passphrase)
    (ssh/add-identity-with-keychain agent private-key-path)))

(defn- ssh-mktemp
  "Create a temporary remote file using the `ssh-session` and the filename
  `prefix`"
  [ssh-session prefix]
  (let [result (ssh/ssh
                ssh-session
                (stevedore/script (println (~lib/make-temp-file ~prefix)))
                :return-map true)]
    (if (zero? (:exit result))
      (string/trim (result :out))
      (condition/raise
       :type :remote-execution-failure
       :message (format
                 "Failed to generate remote temporary file %s" (:err result))
       :exit (:exit result)
       :err (:err result)
       :out (:out result)))))

(defn remote-sudo-cmd
  "Execute remote command.
   Copies `command` to `tmpfile` on the remote node using the `sftp-channel`
   and executes the `tmpfile` as the specified `user`."
  [server ssh-session sftp-channel user tmpfile command
   {:keys [pty] :or {pty true} :as options}]
  (when (not (ssh/connected? ssh-session))
    (condition/raise :type :no-ssh-session
                     :message (format"No ssh session for %s" server)))
  (let [response (ssh/sftp sftp-channel
                           :put (java.io.ByteArrayInputStream.
                                 (.getBytes
                                  (str prolog command \newline)))
                           tmpfile
                           :return-map true)
        response2 (ssh/sftp sftp-channel :ls)]
    (logging/infof
     "Transfering commands to %s:%s : %s" server tmpfile response))
  (let [chmod-result (ssh/ssh
                      ssh-session (str "chmod 755 " tmpfile) :return-map true)]
    (if (pos? (chmod-result :exit))
      (logging/error (str "Couldn't chmod script : "  (chmod-result :err)))))
  (let [cmd (str (sudo-cmd-for user) "./" tmpfile)
        _ (logging/infof "Running %s" cmd)
        [shell stream] (ssh/ssh
                        ssh-session
                        ;; using :in forces a shell ssh-session, rather than
                        ;; exec; some services check for a shell ssh-session
                        ;; before detaching (couchdb being one prime
                        ;; example)
                        :in cmd
                        :out :stream
                        :return-map true
                        :pty pty)
        sb (StringBuilder.)
        buffer-size @ssh-output-buffer-size
        period @output-poll-period
        bytes (byte-array buffer-size)
        read-ouput (fn []
                     (when (pos? (.available stream))
                       (let [num-read (.read stream bytes 0 buffer-size)
                             s (normalise-eol
                                (strip-sudo-password
                                 (String. bytes 0 num-read "UTF-8") user))]
                         (logging/infof "Output: %s\n%s" server s)
                         (.append sb s)
                         s)))]
    (while (ssh/connected? shell)
      (Thread/sleep period)
      (read-ouput))
    (while (read-ouput))
    (.close stream)
    (ssh/ssh ssh-session (str "rm " tmpfile))
    (let [exit (.getExitStatus shell)
          stdout (str sb)]
      (if (zero? exit)
        {:out stdout :exit exit}
        (do
          (logging/errorf "Exit status  : %s" exit)
          {:out stdout :exit exit
           :error {:message (format
                             "Error executing script :\n :cmd %s\n :out %s\n"
                             command stdout)
                   :type :pallet-script-excution-error
                   :script-exit exit
                   :script-out stdout
                   :server server}})))))

(defn remote-sudo
  "Run a sudo command on a server."
  [#^String server #^String command user {:keys [pty] :as options}]
  (ssh/with-ssh-agent [(default-agent)]
    (possibly-add-identity
     ssh/*ssh-agent* (:private-key-path user) (:passphrase user))
    (let [ssh-session (ssh/session server
                               :username (:username user)
                               :password (:password user)
                               :strict-host-key-checking :no)]
      (ssh/with-connection ssh-session
        (let [tmpfile (ssh-mktemp ssh-session "remotesudo")
              sftp-channel (ssh/ssh-sftp ssh-session)]
          (logging/infof "Cmd %s" command)
          (ssh/with-connection sftp-channel
            (remote-sudo-cmd
             server ssh-session sftp-channel user tmpfile command options)))))))

(defn remote-exec
  "Run an ssh exec command on a server."
  [#^String server #^String command user]
  (ssh/with-ssh-agent [(default-agent)]
    (possibly-add-identity
     ssh/*ssh-agent* (:private-key-path user) (:passphrase user))
    (let [ssh-session (ssh/session server
                               :username (:username user)
                               :password (:password user)
                               :strict-host-key-checking :no)]
      (ssh/with-connection ssh-session
        (logging/infof "Exec %s" command)
        (ssh/ssh-exec ssh-session command nil "UTF-8" nil)))))

(defn- ensure-ssh-connection
  "Try ensuring an ssh connection to the server specified in the session."
  [session]
  (let [{:keys [server port user ssh-session sftp-channel tmpfile tmpcpy]
         :as ssh} (:ssh session)]
    (when-not
        (and server
             (if (string? server) (not (string/blank? server)) true)
             user)
      (condition/raise
       :type :session-missing-middleware
       :message (str
                 "The session is missing server ssh connection details.\n"
                 "Add middleware to enable ssh.")))
    (let [ssh-session (or ssh-session
                      (ssh/session
                       server
                       :username (:username user)
                       :strict-host-key-checking :no
                       :port port
                       :password (:password user)))
          _ (.setDaemonThread ssh-session true)
          _ (when-not (ssh/connected? ssh-session) (ssh/connect ssh-session))
          tmpfile (or tmpfile (ssh-mktemp ssh-session "sudocmd"))
          tmpcpy (or tmpcpy (ssh-mktemp ssh-session "tfer"))
          sftp-channel (or sftp-channel (ssh/ssh-sftp ssh-session))
          _ (when-not (ssh/connected? sftp-channel) (ssh/connect sftp-channel))]
      (update-in session [:ssh] merge
                 {:ssh-session ssh-session
                  :tmpfile tmpfile
                  :tmpcpy tmpcpy
                  :sftp-channel sftp-channel}))))

(defn- close-ssh-connection
  "Close any ssh connection to the server specified in the session."
  [[results session flag]]
  (let [{:keys [ssh-session sftp-channel tmpfile tmpcpy]
         :as ssh} (:ssh session)]
    (if ssh
      (do
        (when sftp-channel
          ;; remove tmpfile, tmpcpy
          (ssh/disconnect sftp-channel))
        (when ssh-session
          (ssh/disconnect ssh-session))
        [results (dissoc session :ssh) flag])
      [results session flag])))

(defmacro with-ssh-tunnel
  "Execute the body with an ssh-tunnel available for the ports given in the
   tunnels map. tunnels should be a map from local ports (integers) to either
     1) An integer remote port. Remote host is assumed to be 'localhost'.
     2) A vector of remote host and remote port. eg, [\"yahoo.com\" 80].
   Automatically closes port forwards on completion."
  [session tunnels & body]
  `(let [~session (#'pallet.execute/ensure-ssh-connection ~session)
         ssh-session# (-> ~session :ssh :ssh-session)]
     ;; Set up the port forwards
     (doseq [tunnel# ~tunnels]
       (let [lport# (first tunnel#)
             [rhost# rport#] (if (vector? (second tunnel#))
                               (second tunnel#)
                               ["localhost" (second tunnel#)])]
         (.setPortForwardingL ssh-session# lport# rhost# rport#)))
     (let [body-val# ~@body] ;; Need to hold on to value of body, to return it.
       ;; Close down the port forwards.
       (doseq [tunnel# ~tunnels]
         (let [lport# (first tunnel#)]
           (.delPortForwardingL ssh-session# lport#)))
       body-val#)))

;;; executor functions

(defn bash-on-origin
  "Execute a bash action on the origin"
  [session f]
  (let [{:keys [value session]} (f session)
        result (sh-script value)]
    (logging/infof "Origin cmd\n%s" value)
    (verify-sh-return "for origin cmd" value result)
    [result session]))

(defn transfer-on-origin
  "Transfer files on origin by copying"
  [session f]
  (let [{:keys [value session]} (f session)]
    (logging/info "Local transfer")
    (doseq [[from to] value]
      (logging/infof "Copying %s to %s" from to)
      (io/copy (io/file from) (io/file to)))
    [value session]))

(defn clojure-on-origin
  "Execute a clojure function on the origin"
  [session f]
  (let [{:keys [value session]} (f session)]
    [value session]))

(defn ssh-bash-on-target
  "Execute a bash action on the target via ssh."
  [session f]
  (let [{:keys [ssh] :as session} (ensure-ssh-connection session)
        {:keys [server ssh-session sftp-channel tmpfile tmpcpy user]} ssh
        {:keys [value session]} (f session)]
    (logging/infof "Target %s cmd\n%s" server value)
    [(remote-sudo-cmd server ssh-session sftp-channel user tmpfile value {})
     session]))

(defn- ssh-upload
  "Upload a file to a remote location via sftp"
  [tmpcpy file server ssh-session sftp-channel user tmpfile remote-name]
  (logging/infof
   "Transferring %s to %s:%s via %s" file server remote-name tmpcpy)
  (ssh/sftp
   sftp-channel
   :put (-> file java.io.FileInputStream. java.io.BufferedInputStream.)
   tmpcpy)
  (remote-sudo-cmd
   server ssh-session sftp-channel user tmpfile
   (stevedore/script
    (chmod "0600" ~tmpcpy)
    (mv -f ~tmpcpy ~remote-name))
   {}))

(defn ssh-from-local
  "Transfer a file from the origin machine to the target via ssh."
  [session f]
  (let [{:keys [ssh] :as session} (ensure-ssh-connection session)
        {:keys [server ssh-session sftp-channel tmpfile tmpcpy user]} ssh
        {:keys [value session]} (f session)]
    (doseq [[file remote-name] value
            :let [remote-md5-name (-> remote-name
                                      (string/replace #"\.new$" ".md5")
                                      (string/replace #"-content$" ".md5"))]]
      (logging/debugf "Remote file %s:%s from" server remote-md5-name file)
      (let [md5 (try
                  (filesystem/with-temp-file [md5-copy]
                    (ssh/sftp
                     sftp-channel
                     :get
                     remote-md5-name
                     (.getPath md5-copy))
                    (slurp md5-copy))
                  (catch Exception _ nil))]
        (if md5
          (filesystem/with-temp-file [local-md5-file]
            (logging/debugf "Calculating md5 for %s" file)
            (local-script
             ((~lib/md5sum ~file) ">" ~(.getPath local-md5-file))
             (~lib/normalise-md5 ~(.getPath local-md5-file)))
            (let [local-md5 (slurp local-md5-file)]
              (logging/debugf "md5 check - remote: %s local: %s" md5 local-md5)
              (if (not=
                   (first (string/split md5 #" "))
                   (first (string/split local-md5 #" ")) )
                (ssh-upload
                 tmpcpy file server ssh-session sftp-channel user tmpfile
                 remote-name)
                (logging/infof "%s:%s is already up to date" server remote-name))))
          (ssh-upload
           tmpcpy file server ssh-session sftp-channel user tmpfile
           remote-name))))
    [value session]))

(defn ssh-to-local
  "Transfer a file from the origin machine to the target via ssh."
  [session f]
  (let [{:keys [ssh] :as session} (ensure-ssh-connection session)
        {:keys [server ssh-session sftp-channel tmpfile tmpcpy user]} ssh
        {:keys [value session]} (f session)]
    (doseq [[remote-file local-file] value]
      (logging/infof
       "Transferring file %s from node to %s" remote-file local-file)
      (remote-sudo-cmd
       server ssh-session sftp-channel user tmpfile
       (stevedore/script
        (cp -f ~remote-file ~tmpcpy))
       {})
      (ssh/sftp sftp-channel
                :get tmpcpy
                (-> local-file java.io.FileOutputStream.
                    java.io.BufferedOutputStream.)))
    [value session]))

(defn echo-bash
  "Echo a bash action. Do not execute."
  [session f]
  [(:value (f session)) session])

(defn echo-clojure
  "Echo a clojure action (which returns nil)"
  [session f]
  (let [{:keys [value session]} (f session)]
    ["" session]))

(defn echo-transfer
  "echo transfer of files"
  [session f]
  (let [{:keys [value session]} (f session)]
    (logging/info "Local transfer")
    (doseq [[from to] value]
      (logging/infof "Copying %s to %s" from to))
    [value session]))

;;; executor middleware
(defn execute-with-ssh
  "Execute cmds for the session. Also accepts an IP or hostname as address."
  [handler]
  (fn execute-with-ssh-fn [{:keys [target-node user] :as session}]
    (logging/infof
     "execute-with-ssh on %s %s"
     (compute/group-name target-node)
     (pr-str (compute/node-address target-node)))
    (ssh/with-ssh-agent [(default-agent)]
      (try
        (->
         session
         (assoc :ssh {:port (compute/ssh-port target-node)
                      :server (compute/node-address target-node)
                      :user user})
         (assoc-in [:executor :script/bash :target] ssh-bash-on-target)
         (assoc-in [:executor :transfer/to-local :origin] ssh-to-local)
         (assoc-in [:executor :transfer/from-local :origin] ssh-from-local)
         handler
         close-ssh-connection)
        (catch Exception e
          (logging/error
           e
           "Unexpected exception in execute-with-ssh: probable connection leak")
          (close-ssh-connection session)
          (throw e))))))

(defn execute-target-on-localhost
  "Execute cmds for target on the local machine"
  [handler]
  (fn execute-target-on-localhost-fn [{:keys [target-node user] :as session}]
    (->
     session
     (assoc-in [:executor :script/bash :target] bash-on-origin)
     (assoc-in [:executor :transfer/from-local :origin] transfer-on-origin)
     (assoc-in [:executor :transfer/to-local :origin] transfer-on-origin)
     handler)))

(defn execute-echo
  "Execute cmds for target on the local machine"
  [handler]
  (fn execute-target-on-localhost-fn [{:keys [target-node user] :as session}]
    (->
     session
     (assoc-in [:executor :script/bash :target] echo-bash)
     (assoc-in [:executor :script/bash :origin] echo-bash)
     (assoc-in [:executor :fn/clojure :target] echo-clojure)
     (assoc-in [:executor :fn/clojure :origin] echo-clojure)
     (assoc-in [:executor :transfer/from-local :origin] echo-transfer)
     (assoc-in [:executor :transfer/to-local :origin] echo-transfer)
     handler)))

;; other middleware
(defn ssh-user-credentials
  "Middleware to user the session :user credentials for SSH authentication."
  [handler]
  (fn [session]
    (let [user (:user session)]
      (logging/infof
       "Admin user %s %s" (:username user) (:private-key-path user))
      (possibly-add-identity
       (default-agent) (:private-key-path user) (:passphrase user)))
    (handler session)))
