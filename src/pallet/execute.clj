(ns pallet.execute
  "Exectute commands.  At the moment the only available transport is ssh."
  (:require
   [pallet.action-plan :as action-plan]
   [pallet.action.file :as file]
   [pallet.compute :as compute]
   [pallet.compute.jvm :as jvm]
   [pallet.environment :as environment]
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
  "Execute cmds for the session.
   Runs locally as the current user, so useful for testing."
  [{:keys [root-path] :or {root-path "/tmp/"} :as session}]
  (if (seq (action-plan/get-for-target session))
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
  [server ssh-session sftp-channel user tmpfile command]
  (let [response (ssh/sftp sftp-channel
                           :put (java.io.ByteArrayInputStream.
                                 (.getBytes (str prolog command))) tmpfile
                           :return-map true)]
    (logging/info (format "Transfering commands %s" response)))
  (let [chmod-result (ssh/ssh
                      ssh-session (str "chmod 755 " tmpfile) :return-map true)]
    (if (pos? (chmod-result :exit))
      (logging/error (str "Couldn't chmod script : "  (chmod-result :err)))))
  (let [[shell stream] (ssh/ssh
                        ssh-session
                        ;; using :in forces a shell ssh-session, rather than
                        ;; exec; some services check for a shell ssh-session
                        ;; before detaching (couchdb being one prime
                        ;; example)
                        :in (str (sudo-cmd-for user)
                                 " ~" (:username user) "/" tmpfile)
                        :out :stream
                        :return-map true
                        :pty true)
        sb (StringBuilder.)
        buffer-size 4096
        bytes (byte-array buffer-size)]
    (while (ssh/connected? shell)
      (Thread/sleep 1000)
      (when (pos? (.available stream))
        (let [num-read (.read stream bytes 0 buffer-size)
              s (normalise-eol
                 (strip-sudo-password (String. bytes 0 num-read "UTF-8") user))]
          (logging/info (format "Output: %s" s))
          (.append sb s))))
    (let [exit (.getExitStatus shell)
          stdout (str sb)]
      (when-not (zero? exit)
        (do
          (logging/error (str "Exit status  : " exit))
          (logging/error (str "Output       : " stdout))
          (condition/raise
           :message (format
                     "Error executing script :\n :cmd %s\n :out %s\n"
                     command stdout)
           :type :pallet-script-excution-error
           :script-exit exit
           :script-out stdout
           :server server)))
      (ssh/ssh ssh-session (str "rm " tmpfile))
      {:out stdout :exit exit})))

(defn remote-sudo
  "Run a sudo command on a server."
  [#^String server #^String command user]
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
          (logging/info (format "Cmd %s" command))
          (ssh/with-connection sftp-channel
            (remote-sudo-cmd
             server ssh-session sftp-channel user tmpfile command)))))))

(defn- ensure-ssh-connection
  "Try ensuring an ssh connection to the server specified in the session."
  [session]
  (let [{:keys [server port user ssh-session sftp-channel tmpfile tmpcpy]
         :as ssh} (:ssh session)]
    (when-not (and server user)
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
  [session]
  (let [{:keys [ssh-session sftp-channel tmpfile tmpcpy] :as ssh} (:ssh session)]
    (if ssh
      (do
        (when (and sftp-channel (ssh/connected? sftp-channel))
          ;; remove tmpfile, tmpcpy
          (ssh/disconnect sftp-channel))
        (when (and ssh-session (ssh/connected? ssh-session))
          (ssh/disconnect ssh-session))
        (dissoc session :ssh))
      session)))

;;; executor functions

(defn bash-on-origin
  "Execute a bash action on the origin"
  [session f]
  (let [{:keys [value session]} (f session)
        result (sh-script value)]
    (logging/info (format "Origin cmd\n%s" value))
    (verify-sh-return "for origin cmd" value result)
    [result session]))

(defn transfer-on-origin
  "Transfer files on origin by copying"
  [session f]
  (let [{:keys [value session]} (f session)]
    (logging/info "Local transfer")
    (doseq [[from to] value]
      (logging/info (format "Copying %s to %s" from to))
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
    (logging/info (format "Target cmd\n%s" value))
    [(remote-sudo-cmd server ssh-session sftp-channel user tmpfile value)
     session]))

(defn ssh-from-local
  "Transfer a file from the origin machine to the target via ssh."
  [session f]
  (let [{:keys [ssh] :as session} (ensure-ssh-connection session)
        {:keys [server ssh-session sftp-channel tmpfile tmpcpy user]} ssh
        {:keys [value session]} (f session)]
    (doseq [[file remote-name] value]
      (logging/info
       (format
        "Transferring file %s to node @ %s via %s" file remote-name tmpcpy))
      (ssh/sftp
       sftp-channel
       :put (-> file java.io.FileInputStream. java.io.BufferedInputStream.)
       tmpcpy)
      (remote-sudo-cmd
       server ssh-session sftp-channel user tmpfile
       (stevedore/script
        (chmod "0600" ~tmpcpy)
        (mv -f ~tmpcpy ~remote-name))))
    [value session]))

(defn ssh-to-local
  "Transfer a file from the origin machine to the target via ssh."
  [session f]
  (let [{:keys [ssh] :as session} (ensure-ssh-connection session)
        {:keys [server ssh-session sftp-channel tmpfile tmpcpy user]} ssh
        {:keys [value session]} (f session)]
    (doseq [[remote-file local-file] value]
      (logging/info
       (format
        "Transferring file %s from node to %s" remote-file local-file))
      (remote-sudo-cmd
       server ssh-session sftp-channel user tmpfile
       (stevedore/script
        (cp -f ~remote-file ~tmpcpy)))
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
      (logging/info (format "Copying %s to %s" from to)))
    [value session]))

;;; executor middleware
(defn execute-with-ssh
  "Execute cmds for the session. Also accepts an IP or hostname as address."
  [handler]
  (fn execute-with-ssh-fn [{:keys [target-node user] :as session}]
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
      (logging/info (format "Using identity at %s" (:private-key-path user)))
      (possibly-add-identity
       (default-agent) (:private-key-path user) (:passphrase user)))
    (handler session)))
