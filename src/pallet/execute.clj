(ns pallet.execute
  "Exectute commands.  At the moment the only available transport is ssh."
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.compute :as compute]
   [pallet.compute.jvm :as jvm]
   [pallet.environment :as environment]
   [pallet.resource.file :as file]
   [pallet.script :as script]
   [pallet.script.lib :as lib]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [clj-ssh.ssh :as ssh]
   [clojure.string :as string]
   [clojure.contrib.condition :as condition]
   [clojure.java.io :as io]
   [clojure.contrib.shell :as shell]
   [clojure.contrib.logging :as logging]))

(def prolog
  (str "#!/usr/bin/env bash\n"
       stevedore/hashlib))

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
(script/defimpl sudo-no-password [#{:centos-5.3 :os-x :darwin :debian}] []
  ("/usr/bin/sudo"))

(defn sudo-cmd-for
  "Construct a sudo command prefix for the specified user."
  [user]
  (if (or (= (:username user) "root") (:no-sudo user))
    ""
    (if-let [pw (:sudo-password user)]
      (str "echo \"" (or (:password user) pw) "\" | /usr/bin/sudo -S")
      (stevedore/script (~sudo-no-password)))))

;;; local script execution
(defn system
  "Launch a system process, return a map containing the exit code, standard
  output and standard error of the process."
  [cmd]
  (let [result (apply shell/sh :return-map true (.split cmd " "))]
    (when (pos? (result :exit))
      (logging/error (str "Command failed: " cmd "\n" (result :err))))
    (logging/info (result :out))
    result))

(defn bash [cmds]
  (utils/with-temp-file [file cmds]
    (system (str "/usr/bin/env bash " (.getPath file)))))

(defn local-cmds
  "Run local cmds on a target."
  [#^String commands]
  (let [execute (fn [cmd] ((second cmd)))
        rv (doall (map execute (filter #(= :origin (first %)) commands)))]
    rv))

(defn sh-script
  "Run a script on local machine."
  [command]
  (logging/trace
   (format "sh-script %s" command))
  (let [tmp (java.io.File/createTempFile "pallet" "script")]
    (try
      (io/copy (str prolog command) tmp)
      (shell/sh "chmod" "+x" (.getPath tmp))
      (let [result (shell/sh "bash" (.getPath tmp) :return-map true)]
        (when-not (zero? (:exit result))
          (logging/error
           (format "Command failed: %s\n%s" command (:err result))))
        (logging/info (:out result))
        result)
      (finally  (.delete tmp)))))

(defmacro local-script
  "Run a script on the local machine, setting up stevedore to produce the
   correct target specific code"
  [& body]
  `(script/with-template
     [(jvm/os-family)]
     (sh-script
      (stevedore/script
       ~@body))))

(defn verify-sh-return
  "Verify the return code of a sh execution"
  [msg cmd result]
  (when-not (zero? (:exit result))
    (condition/raise
     :message (format
               "Error executing script %s\n :cmd %s :out %s\n :err %s"
               msg cmd (:out result) (:err result))
     :type :pallet-script-excution-error
     :script-exit (:exit result)
     :script-out  (:out result)
     :script-err (:err result)
     :server "localhost"))
  result)

(defmacro local-checked-script
  "Run a script on the local machine, setting up stevedore to produce the
   correct target specific code.  The return code is checked."
  [msg & body]
  `(script/with-template
     [(jvm/os-family)]
     (let [cmd# (stevedore/checked-script ~msg ~@body)]
       (verify-sh-return ~msg cmd# (sh-script cmd#)))))

(defn local-sh-cmds
  "Execute cmds for the request.
   Runs locally as the current user, so useful for testing."
  [{:keys [root-path] :or {root-path "/tmp/"} :as request}]
  (if (seq (action-plan/get-for-target request))
    (letfn [(execute-bash
             [cmdstring]
             (logging/info (format "Cmd %s" cmdstring))
             (sh-script cmdstring))
            (transfer
             [transfers]
             (logging/info (format "Local transfer"))
             (doseq [[from to] transfers]
               (logging/info (format "Copying %s to %s" from to))
               (io/copy (io/file from) (io/file to))))]
      (action-plan/execute-for-target
       request
       {:script/bash execute-bash
        :fn/clojure (fn [& _])
        :transfer/to-local transfer
        :transfer/from-local transfer}))
    [nil request]))

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
  "Create a temporary remote file using the ssh `session` and the filename
  `prefix`"
  [session prefix]
  (let [result (ssh/ssh
                session
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
  [server session sftp-channel user tmpfile command]
  (let [response (ssh/sftp sftp-channel
                           :put (java.io.ByteArrayInputStream.
                                 (.getBytes (str prolog command))) tmpfile
                           :return-map true)]
    (logging/info (format "Transfering commands %s" response)))
  (let [chmod-result (ssh/ssh
                      session (str "chmod 755 " tmpfile) :return-map true)]
    (if (pos? (chmod-result :exit))
      (logging/error (str "Couldn't chmod script : "  (chmod-result :err)))))
  (let [script-result (ssh/ssh
                       session
                       ;; using :in forces a shell session, rather than
                       ;; exec; some services check for a shell session
                       ;; before detaching (couchdb being one prime
                       ;; example)
                       :in (str (sudo-cmd-for user)
                                " ~" (:username user) "/" tmpfile)
                       :return-map true
                       :pty true)]
    (let [stdout (normalise-eol
                  (strip-sudo-password (script-result :out) user))
          stderr (normalise-eol
                  (strip-sudo-password (get script-result :err "") user))]
      (if (zero? (script-result :exit))
        (logging/info stdout)
        (do
          (logging/error (str "Exit status  : " (script-result :exit)))
          (logging/error (str "Output       : " stdout))
          (logging/error (str "Error output : " stderr))
          (condition/raise
           :message (format
                     "Error executing script :\n :cmd %s\n :out %s\n :err %s"
                     command stdout stderr)
           :type :pallet-script-excution-error
           :script-exit (script-result :exit)
           :script-out stdout
           :script-err stderr
           :server server)))
      (ssh/ssh session (str "rm " tmpfile))
      {:out stdout :err stderr :exit (:exit script-result)})))

(defn remote-sudo
  "Run a sudo command on a server."
  [#^String server #^String command user]
  (ssh/with-ssh-agent [(default-agent)]
    (possibly-add-identity
     ssh/*ssh-agent* (:private-key-path user) (:passphrase user))
    (let [session (ssh/session server
                               :username (:username user)
                               :password (:password user)
                               :strict-host-key-checking :no)]
      (ssh/with-connection session
        (let [tmpfile (ssh-mktemp session "remotesudo")
              sftp-channel (ssh/ssh-sftp session)]
          (logging/info (format "Cmd %s" command))
          (ssh/with-connection sftp-channel
            (remote-sudo-cmd
             server session sftp-channel user tmpfile command)))))))

(defn- ensure-ssh-connection
  "Try ensuring an ssh connection to the server specified in the request."
  [request]
  (let [{:keys [server port user session sftp-channel tmpfile tmpcpy]
         :as ssh} (:ssh request)]
    (when-not (and server user)
      (condition/raise
       :type :request-missing-middleware
       :message (str
                 "The request is missing server ssh connection details.\n"
                 "Add middleware to enable ssh.")))
    (let [session (or session
                      (ssh/session
                       server
                       :username (:username user)
                       :strict-host-key-checking :no
                       :port port
                       :password (:password user)))
          _ (when-not (ssh/connected? session) (ssh/connect session))
          tmpfile (or tmpfile (ssh-mktemp session "sudocmd"))
          tmpcpy (or tmpcpy (ssh-mktemp session "tfer"))
          sftp-channel (or sftp-channel (ssh/ssh-sftp session))
          _ (when-not (ssh/connected? sftp-channel) (ssh/connect sftp-channel))]
      (update-in request [:ssh] merge
                 {:session session
                  :tmpfile tmpfile
                  :tmpcpy tmpcpy
                  :sftp-channel sftp-channel}))))

(defn- close-ssh-connection
  "Close any ssh connection to the server specified in the request."
  [request]
  (let [{:keys [session sftp-channel tmpfile tmpcpy] :as ssh} (:ssh request)]
    (if ssh
      (do
        (when (and sftp-channel (ssh/connected? sftp-channel))
          ;; remove tmpfile, tmpcpy
          (ssh/disconnect sftp-channel))
        (when (and session (ssh/connected? session))
          (ssh/disconnect session))
        (dissoc request :ssh))
      request)))

;;; executor functions

(defn bash-on-origin
  "Execute a bash action on the origin"
  [request f]
  (let [{:keys [value request]} (f request)
        result (sh-script value)]
    (logging/info (format "Origin cmd\n%s" value))
    (verify-sh-return "for origin cmd" value result)
    [result request]))

(defn transfer-on-origin
  "Transfer files on origin by copying"
  [request f]
  (let [{:keys [value request]} (f request)]
    (logging/info "Local transfer")
    (doseq [[from to] value]
      (logging/info (format "Copying %s to %s" from to))
      (io/copy (io/file from) (io/file to)))
    [value request]))

(defn clojure-on-origin
  "Execute a clojure function on the origin"
  [request f]
  (let [{:keys [value request]} (f request)]
    [value request]))

(defn ssh-bash-on-target
  "Execute a bash action on the target via ssh."
  [request f]
  (let [{:keys [ssh] :as request} (ensure-ssh-connection request)
        {:keys [server session sftp-channel tmpfile tmpcpy user]} ssh
        {:keys [value request]} (f request)]
    (logging/info (format "Target cmd\n%s" value))
    [(remote-sudo-cmd server session sftp-channel user tmpfile value)
     request]))

(defn ssh-from-local
  "Transfer a file from the origin machine to the target via ssh."
  [request f]
  (let [{:keys [ssh] :as request} (ensure-ssh-connection request)
        {:keys [server session sftp-channel tmpfile tmpcpy user]} ssh
        {:keys [value request]} (f request)]
    (doseq [[file remote-name] value]
      (logging/info
       (format
        "Transferring file %s to node @ %s via %s" file remote-name tmpcpy))
      (ssh/sftp
       sftp-channel
       :put (-> file java.io.FileInputStream. java.io.BufferedInputStream.)
       tmpcpy)
      (remote-sudo-cmd
       server session sftp-channel user tmpfile
       (stevedore/script
        (chmod "0600" ~tmpcpy)
        (mv -f ~tmpcpy ~remote-name))))
    [value request]))

(defn ssh-to-local
  "Transfer a file from the origin machine to the target via ssh."
  [request f]
  (let [{:keys [ssh] :as request} (ensure-ssh-connection request)
        {:keys [server session sftp-channel tmpfile tmpcpy user]} ssh
        {:keys [value request]} (f request)]
    (doseq [[remote-file local-file] value]
      (logging/info
       (format
        "Transferring file %s from node to %s" remote-file local-file))
      (remote-sudo-cmd
       server session sftp-channel user tmpfile
       (stevedore/script
        (cp -f ~remote-file ~tmpcpy)))
      (ssh/sftp sftp-channel
                :get tmpcpy
                (-> local-file java.io.FileOutputStream.
                    java.io.BufferedOutputStream.)))
    [value request]))

(defn echo-bash
  "Echo a bash action. Do not execute."
  [request f]
  [(:value (f request)) request])

(defn echo-transfer
  "echo transfer of files"
  [request f]
  (let [{:keys [value request]} (f request)]
    (logging/info "Local transfer")
    (doseq [[from to] value]
      (logging/info (format "Copying %s to %s" from to)))
    [value request]))

;;; executor middleware
(defn execute-with-ssh
  "Execute cmds for the request. Also accepts an IP or hostname as address."
  [handler]
  (fn execute-with-ssh-fn [{:keys [target-node user] :as request}]
    (ssh/with-ssh-agent [(default-agent)]
      (try
        (->
         request
         (assoc :ssh {:port (compute/ssh-port target-node)
                      :server (compute/node-address target-node)
                      :user user})
         (assoc-in [:executor :script/bash :target] ssh-bash-on-target)
         (assoc-in [:executor :transfer/to-local :origin] ssh-to-local)
         (assoc-in [:executor :transfer/from-local :origin] ssh-from-local)
         handler
         close-ssh-connection)
        (catch Exception e
          (close-ssh-connection request)
          (throw e))))))

(defn execute-target-on-localhost
  "Execute cmds for target on the local machine"
  [handler]
  (fn execute-target-on-localhost-fn [{:keys [target-node user] :as request}]
    (->
     request
     (assoc-in [:executor :script/bash :target] bash-on-origin)
     (assoc-in [:executor :transfer/from-local :origin] transfer-on-origin)
     (assoc-in [:executor :transfer/to-local :origin] transfer-on-origin)
     handler)))

(defn execute-echo
  "Execute cmds for target on the local machine"
  [handler]
  (fn execute-target-on-localhost-fn [{:keys [target-node user] :as request}]
    (->
     request
     (assoc-in [:executor :script/bash :target] echo-bash)
     (assoc-in [:executor :script/bash :origin] echo-bash)
     (assoc-in [:executor :transfer/from-local :origin] echo-transfer)
     (assoc-in [:executor :transfer/to-local :origin] echo-transfer)
     handler)))

;; other middleware
(defn ssh-user-credentials
  "Middleware to user the request :user credentials for SSH authentication."
  [handler]
  (fn [request]
    (let [user (:user request)]
      (logging/info (format "Using identity at %s" (:private-key-path user)))
      (possibly-add-identity
       (default-agent) (:private-key-path user) (:passphrase user)))
    (handler request)))
