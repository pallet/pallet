(ns pallet.utils
  (:require
   [clojure.contrib.shell :as shell]
   [clojure.contrib.io :as io]
   [clojure.contrib.string :as string]
   [clojure.contrib.pprint :as pprint]
   [clojure.contrib.condition :as condition])
  (:use
   clojure.contrib.logging
   clj-ssh.ssh
   clojure.contrib.def))

(defn pprint-lines [s]
  (pprint/pprint (seq (.split #"\r?\n" s))))

(defn quoted [s]
  (str "\"" s "\""))

(defn underscore [s]
  (apply str (interpose "_"  (.split s "-"))))

(defn as-string [arg]
  (cond
   (symbol? arg) (name arg)
   (keyword? arg) (name arg)
   :else (str arg)))

(defn blank?
  [x]
  (or (nil? x) (and (string? x) (= x ""))))

(defn resource-path [name]
  (let [loader (.getContextClassLoader (Thread/currentThread))
        resource (. loader getResource name)]
    (when resource
      (.getFile resource))))


(defn load-resource
  [name]
  (let [loader (.getContextClassLoader (Thread/currentThread))]
    (.getResourceAsStream loader name)))

(defn load-resource-url
  [name]
  (with-open [stream (.getContent name)
              r (new java.io.BufferedReader
                     (new java.io.InputStreamReader
                          stream (.name (java.nio.charset.Charset/defaultCharset))))]
    (let [sb (new StringBuilder)]
      (loop [c (.read r)]
        (if (neg? c)
          (str sb)
          (do
            (.append sb (char c))
            (recur (.read r))))))))

(defn slurp-resource
  "Reads the resource named by name using the encoding enc into a string
  and returns it."
  ([name] (slurp-resource name (.name (java.nio.charset.Charset/defaultCharset))))
  ([#^String name #^String enc]
     (let [stream (load-resource name)]
       (when stream
         (with-open [stream stream
                     r (new java.io.BufferedReader
                            (new java.io.InputStreamReader
                                 stream enc))]
           (let [sb (new StringBuilder)]
             (loop [c (.read r)]
               (if (neg? c)
                 (str sb)
                 (do
                   (.append sb (char c))
                   (recur (.read r)))))))))))

(defn resource-properties [name]
  (let [loader (.getContextClassLoader (Thread/currentThread))]
    (with-open [stream (.getResourceAsStream loader name)]
      (let [properties (new java.util.Properties)]
        (.load properties stream)
        (let [keysseq (enumeration-seq (. properties propertyNames))]
          (reduce (fn [a b] (assoc a b (. properties getProperty b)))
                  {} keysseq))))))

(defn slurp-as-byte-array
  [#^java.io.File file]
  (let [size (.length file)
        bytes #^bytes (byte-array size)
        stream (new java.io.FileInputStream file)]
    bytes))


(defn default-private-key-path []
  (str (System/getProperty "user.home") "/.ssh/id_rsa"))
(defn default-public-key-path []
  (str (System/getProperty "user.home") "/.ssh/id_rsa.pub"))

(defrecord User
  [username public-key-path private-key-path passphrase
   password sudo-password no-sudo])

(defn make-user
  "Creates a User record with the given username and options. Generally used
   in conjunction with *admin-user* and pallet.core/with-admin-user.
   Some options have default values:

   :public-key-path (defaults to ~/.ssh/id_rsa.pub)
   :private-key-path (defaults to ~/.ssh/id_rsa)
   :sudo-password (defaults to :password)"
  [username & {:as options}]
  (merge (User. username nil nil nil nil nil nil)
    {:private-key-path (default-private-key-path)
     :public-key-path (default-public-key-path)
     :sudo-password (:password options)}
    options))

(defvar *admin-user*
  (make-user (or (. System getProperty "pallet.admin.username")
                 (. System getProperty "user.name")))
  "The admin user is used for running remote admin commands that require root
   permissions.  The default admin user is taken from the pallet.admin.username
   property.  If not specified then the user.name property is used.")

(defn system
  "Launch a system process, return a map containing the exit code, standard
  output and standard error of the process."
  [cmd]
  (let [result (apply shell/sh :return-map true (.split cmd " "))]
    (when (pos? (result :exit))
      (error (str "Command failed: " cmd "\n" (result :err))))
    (info (result :out))
    result))

(defmacro with-temp-file [[varname content] & body]
  `(let [~varname (java.io.File/createTempFile "stevedore", ".tmp")]
     (io/copy ~content ~varname)
     (let [rv# (do ~@body)]
       (.delete ~varname)
       rv#)))

(defn bash [cmds]
  (with-temp-file [file cmds]
    (system (str "/usr/bin/env bash " (.getPath file)))))

(def *file-transfers* {})

(defn register-file-transfer!
  [local-file]
  (let [f (clojure.contrib.io/file local-file)]
    (when-not (and (.exists f) (.isFile f) (.canRead f))
      (throw (IllegalArgumentException.
               (format "'%s' does not exist, is a directory, or is unreadable; cannot register it for transfer" local-file))))
    ;; need to eagerly determine a destination for the file, as crates will need
    ;; to know where to find the transferred file
    (let [remote-name (format "pallet-transfer-%s-%s"
                        (java.util.UUID/randomUUID)
                        (.length f))] ; silly UUID collision paranoia
      (set! *file-transfers* (assoc *file-transfers* f remote-name))
      remote-name)))

(defonce default-agent-atom (atom nil))
(defn default-agent
  []
  (or @default-agent-atom
      (swap! default-agent-atom
             (fn [agent]
               (if agent
                 agent
                 (create-ssh-agent false))))))

(defn possibly-add-identity
  [agent private-key-path passphrase]
  (if passphrase
    (add-identity agent private-key-path passphrase)
    (add-identity-with-keychain agent private-key-path)))

(defn remote-sudo
  "Run a sudo command on a server."
  [#^String server #^String command user]
  (with-ssh-agent [(default-agent)]
    (possibly-add-identity
     *ssh-agent* (:private-key-path user) (:passphrase user))
    (let [session (session server
                           :username (:username user)
                           :strict-host-key-checking :no)]
      (with-connection session
        (let [prefix (if (:password user)
                       (str "echo \"" (:password user) "\" | sudo -S ")
                       "sudo ")
              cmd (str prefix command)
              result (ssh session cmd :return-map true)]
          (info (result :out))
          (when (not (zero? (result :exit)))
            (error (str "Exit status " (result :exit)))
            (error (result :err)))
          result)))))

(def prolog "#!/usr/bin/env bash\n")

(defn- strip-sudo-password
  "Elides the user's password or sudo-password from the given ssh output."
  [#^String s user]
  (.replace s
    (format "\"%s\"" (or (:password user) (:sudo-password user)))
    "XXXXXXX"))

(defn sudo-cmd-for [user]
  (if (or (= (:username user) "root") (:no-sudo user))
    ""
    (if-let [pw (:sudo-password user)]
      (str "echo \"" (or (:password user) pw) "\" | /usr/bin/sudo -S")
      "/usr/bin/sudo -n")))

(defn remote-sudo-script
  "Run a sudo script on a server."
  [#^String server #^String command user & options]
  (with-ssh-agent [(default-agent)]
    (possibly-add-identity *ssh-agent* (:private-key-path user) (:passphrase user))
    (let [options (if (seq options) (apply array-map options) {})
          session (session server
                           :username (:username user)
                           :strict-host-key-checking :no
                           :port (or (options :port) 22)
                           :password (:password user))]
      (with-connection session
        (let [mktemp-result (ssh
                             session "mktemp sudocmdXXXXX" :return-map true)
              tmpfile (string/chomp (mktemp-result :out))
              channel (ssh-sftp session)]
          (assert (zero? (mktemp-result :exit)))
          (sftp channel :put (java.io.ByteArrayInputStream.
                              (.getBytes (str prolog command))) tmpfile)
          (doseq [[file remote-name] *file-transfers*]
            (info
             (format "Transferring file %s to node @ %s" file remote-name))
            (sftp channel
                  :put (-> file java.io.FileInputStream.
                           java.io.BufferedInputStream.)
                  remote-name)
            (sftp channel :chmod 0600 remote-name))
          (let [chmod-result (ssh session (str "chmod 755 " tmpfile)
                                  :return-map true)]
            (if (pos? (chmod-result :exit))
              (error (str "Couldn't chmod script : " ) (chmod-result :err))))
          (let [script-result (ssh
                               session
                               ;; using :in forces a shell session, rather than
                               ;; exec; some services check for a shell session
                               ;; before detaching (couchdb being one prime
                               ;; example)
                               :in (str (sudo-cmd-for user)
                                        " ~" (:username user) "/" tmpfile)
                               :return-map true
                               :pty true)]
            (let [stdout (strip-sudo-password (script-result :out) user)
                  stderr (strip-sudo-password (get script-result :err "") user)]
              (if (zero? (script-result :exit))
                (info stdout)
                (do
                  (error (str "Exit status  : " (script-result :exit)))
                  (error (str "Output       : " stdout))
                  (error (str "Error output : " stderr))
                  (condition/raise
                   :script-exit (script-result :exit)
                   :script-out stdout
                   :script-err stderr
                   :server server))))
            (ssh session (str "rm " tmpfile))
            (doseq [[file remote-name] *file-transfers*]
              (ssh session (str "rm " remote-name)))
            script-result))))))



(defn remote-sudo-cmd
  [server session sftp-channel user tmpfile command]
  (let [response (sftp sftp-channel
                       :put (java.io.ByteArrayInputStream.
                             (.getBytes (str prolog command))) tmpfile
                             :return-map true)]
    (info (format "Transfering commands %s" response)))
  (let [chmod-result (ssh session (str "chmod 755 " tmpfile) :return-map true)]
    (if (pos? (chmod-result :exit))
      (error (str "Couldn't chmod script : " ) (chmod-result :err))))
  (let [script-result (ssh
                       session
                       ;; using :in forces a shell session, rather than
                       ;; exec; some services check for a shell session
                       ;; before detaching (couchdb being one prime
                       ;; example)
                       :in (str (sudo-cmd-for user)
                                " ~" (:username user) "/" tmpfile)
                       :return-map true
                       :pty true)]
    (let [stdout (strip-sudo-password (script-result :out) user)
          stderr (strip-sudo-password (get script-result :err "") user)]
      (if (zero? (script-result :exit))
        (info stdout)
        (do
          (error (str "Exit status  : " (script-result :exit)))
          (error (str "Output       : " stdout))
          (error (str "Error output : " stderr))
          (condition/raise
           :script-exit (script-result :exit)
           :script-out stdout
           :script-err stderr
           :server server))))
    (ssh session (str "rm " tmpfile))
    script-result))

(defn remote-sudo-cmds
  "Run cmds on a target."
  [#^String server commands user options]
  (with-ssh-agent [(default-agent)]
    (let [options (apply array-map options)
          session (session server
                           :username (:username user)
                           :strict-host-key-checking :no
                           :port (or (options :port) 22)
                           :password (:password user))]
      (with-connection session
        (let [mktemp-result (ssh
                             session "mktemp sudocmdXXXXX" :return-map true)
              tmpfile (string/chomp (mktemp-result :out))
              sftp-channel (ssh-sftp session)]
          (with-connection sftp-channel
            (assert (zero? (mktemp-result :exit)))
            (doseq [[file remote-name] *file-transfers*]
              (info
               (format "Transferring file %s to node @ %s" file remote-name))
              (sftp sftp-channel
                    :put (-> file java.io.FileInputStream.
                             java.io.BufferedInputStream.)
                    remote-name)
              (sftp sftp-channel :chmod 0600 remote-name))

            (doseq [cmds commands
                    cmd cmds]
              (clojure.contrib.logging/info (format "Cmd %s" cmd))
              (if (string? cmd)
                (remote-sudo-cmd server session sftp-channel user tmpfile cmd)
                (doseq [local-cmd cmd]
                  (local-cmd))))
            (doseq [[file remote-name] *file-transfers*]
              (ssh session (str "rm " remote-name)))))))))


(defn sh-script
  "Run a script on local machine."
  [command]
  (let [tmp (java.io.File/createTempFile "pallet" "script")]
    (try
     (io/copy command tmp)
     (shell/sh "chmod" "+x" (.getPath tmp))
     (let [result (shell/sh "bash" (.getPath tmp) :return-map true)]
       (when (pos? (result :exit))
         (error (str "Command failed: " command "\n" (result :err))))
       (info (result :out))
       result)
     (finally  (.delete tmp)))))

(defn map-with-keys-as-symbols
  [m]
  (letfn [(to-symbol [x]
                     (cond
                      (symbol? x) x
                      (string? x) (symbol x)
                      (keyword? x) (symbol (name x))))]
    (zipmap (map to-symbol (keys m)) (vals m))))
