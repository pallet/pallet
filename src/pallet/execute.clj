(ns pallet.execute
  "Exectute commands.  At the moment the only available transport is ssh."
  (:require
   [pallet.utils :as utils]
   [pallet.resource :as resource]
   [clj-ssh.ssh :as ssh]
   [clojure.string :as string]
   [clojure.contrib.condition :as condition]
   [clojure.contrib.io :as io]
   [clojure.contrib.shell :as shell]
   [clojure.contrib.logging :as logging]))

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


(def prolog "#!/usr/bin/env bash\n")

(defn- normalise-eol
  "Convert eol into platform specific value"
  [#^String s]
  (string/replace s #"[\r\n]+" (str \newline)))

(defn- strip-sudo-password
  "Elides the user's password or sudo-password from the given ssh output."
  [#^String s user]
  (string/replace
   s (format "\"%s\"" (or (:password user) (:sudo-password user))) "XXXXXXX"))

(defn sudo-cmd-for [user]
  (if (or (= (:username user) "root") (:no-sudo user))
    ""
    (if-let [pw (:sudo-password user)]
      (str "echo \"" (or (:password user) pw) "\" | /usr/bin/sudo -S")
      "/usr/bin/sudo -n")))


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

(defn remote-sudo
  "Run a sudo command on a server."
  [#^String server #^String command user]
  (ssh/with-ssh-agent [(default-agent)]
    (possibly-add-identity
     ssh/*ssh-agent* (:private-key-path user) (:passphrase user))
    (let [session (ssh/session server
                               :username (:username user)
                               :strict-host-key-checking :no)]
      (ssh/with-connection session
        (let [prefix (if (:password user)
                       (str "echo \"" (:password user) "\" | sudo -S ")
                       "sudo ")
              cmd (str prefix command)
              result (ssh/ssh session cmd :return-map true)]
          (logging/info (result :out))
          (when (not (zero? (result :exit)))
            (logging/error (str "Exit status " (result :exit)))
            (logging/error (result :err)))
          result)))))


(defn remote-sudo-cmd
  [server session sftp-channel user tmpfile command]
  (let [response (ssh/sftp sftp-channel
                           :put (java.io.ByteArrayInputStream.
                                 (.getBytes (str prolog command))) tmpfile
                           :return-map true)]
    (logging/info (format "Transfering commands %s" response)))
  (let [chmod-result (ssh/ssh
                      session (str "chmod 755 " tmpfile) :return-map true)]
    (if (pos? (chmod-result :exit))
      (logging/error (str "Couldn't chmod script : " ) (chmod-result :err))))
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
           :message (str "Error executing script : " stdout)
           :type :pallet-script-excution-error
           :script-exit (script-result :exit)
           :script-out stdout
           :script-err stderr
           :server server)))
      (ssh/ssh session (str "rm " tmpfile))
      {:out stdout :err stderr :exit (:exit script-result)})))


(defn execute-ssh-cmds
  "Run cmds on a target."
  [#^String server request user options]
  (ssh/with-ssh-agent [(default-agent)]
    (let [options (apply array-map options)
          session (ssh/session server
                               :username (:username user)
                               :strict-host-key-checking :no
                               :port (or (options :port) 22)
                               :password (:password user))]
      (ssh/with-connection session
        (let [mktemp-result (ssh/ssh
                             session "mktemp sudocmdXXXXX" :return-map true)
              tmpfile (string/trim (mktemp-result :out))
              sftp-channel (ssh/ssh-sftp session)]
          (ssh/with-connection sftp-channel
            (assert (zero? (mktemp-result :exit)))
            (letfn [(execute
                     [cmdstring]
                     (logging/info (format "Cmd %s" cmdstring))
                     (doseq [[file remote-name] resource/*file-transfers*]
                       (logging/info
                        (format
                         "Transferring file %s to node @ %s"
                         file remote-name))
                       (ssh/sftp sftp-channel
                                 :put (-> file java.io.FileInputStream.
                                          java.io.BufferedInputStream.)
                                 remote-name)
                       (ssh/sftp sftp-channel :chmod 0600 remote-name))
                     (let [rv (remote-sudo-cmd
                               server session sftp-channel
                               user tmpfile cmdstring)]
                       (doseq [[file remote-name] resource/*file-transfers*]
                         (ssh/ssh session (str "rm " remote-name)))
                       rv))]
              (resource/execute-commands request execute))))))))

(defn ssh-cmds
  "Execute cmds for the request.
   Also accepts an IP or hostname as anode."
  [{:keys [address commands user ssh-port] :as request}]
  (when commands
    (let [options (if ssh-port [:port ssh-port] [])]
      (execute-ssh-cmds address request user options))))

(defn local-cmds
  "Run local cmds on a target."
  [#^String commands]
  (let [execute (fn [cmd] ((second cmd)))
        rv (doall (map execute (filter #(= :local (first %)) commands)))]
    rv))

(defn sh-script
  "Run a script on local machine."
  [command]
  (let [tmp (java.io.File/createTempFile "pallet" "script")]
    (try
     (io/copy command tmp)
     (shell/sh "chmod" "+x" (.getPath tmp))
     (let [result (shell/sh "bash" (.getPath tmp) :return-map true)]
       (when-not (zero? (:exit result))
         (logging/error
          (format "Command failed: %s\n%s" command (:err result))))
       (logging/info (:out result))
       result)
     (finally  (.delete tmp)))))
