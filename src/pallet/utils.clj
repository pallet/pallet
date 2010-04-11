(ns pallet.utils
  (:require [clojure.contrib.str-utils2 :as string])
  (:use clojure.contrib.logging
        [clj-ssh.ssh]
        [clojure.contrib.def]
        [clojure.contrib.shell-out :only [sh]]
        [clojure.contrib.pprint :only [pprint]]
        [clojure.contrib.duck-streams :as io]))

(defn pprint-lines [s]
  (pprint (seq (.split #"\r?\n" s))))

(defn quoted [s]
  (str "\"" s "\""))

(defn underscore [s]
  (apply str (interpose "_"  (.split s "-"))))

(defn as-string [arg]
  (cond
   (symbol? arg) (name arg)
   (keyword? arg) (name arg)
   :else (str arg)))

(defn cmd-join [cmds]
  (str
   (string/join \newline (map #(string/trim %) cmds))
   \newline))

(defn resource-path [name]
  (let [loader (.getContextClassLoader (Thread/currentThread))
        resource (. loader getResource name)]
    (when resource
      (.getFile resource))))

(defn load-resource
  [name]
  (let [loader (.getContextClassLoader (Thread/currentThread))]
    (.getResourceAsStream loader name)))

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
  (str (. System getProperty "user.home") "/.ssh/id_rsa"))
(defn default-public-key-path []
  (str (. System getProperty "user.home") "/.ssh/id_rsa.pub"))

(defn make-user
  "Create a description of the admin user to be created and used for running
   configuration actions on the administered nodes."
  [username & options]
  (let [options (if (first options) (apply array-map options) {})]
    {:username username
     :password (options :password)
     :private-key-path (or (options :private-key-path)
                           (default-private-key-path))
     :public-key-path (or (options :public-key-path)
                          (default-public-key-path))
     :sudo-password (options :password)}))


(defvar *admin-user*
  (make-user (. System getProperty "user.name"))
  "The admin user is used for running remote admin commands that require root
   permissions.")

(defn system
  "Launch a system process, return a map containing the exit code, stahdard
  output and standard error of the process."
  [cmd]
  (let [result (apply sh :return-map true (.split cmd " "))]
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


(defn remote-sudo
  "Run a sudo command on a server."
  [#^java.net.InetAddress server #^String command user]
  (with-ssh-agent []
    (add-identity (:private-key-path user))
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

(defn remote-sudo-script
  "Run a sudo script on a server."
  [#^java.net.InetAddress server #^String command user & options]
  (with-ssh-agent []
    (add-identity (:private-key-path user))
    (let [options (if (seq options) (apply array-map options) {})
          session (session server
                           :username (user :username)
                           :strict-host-key-checking :no
                           :port (or (options :port) 22)
                           :password (user :password))]
      (with-connection session
        (let [mktemp-result (ssh session "mktemp sudocmdXXXXX" :return-map true)
              tmpfile (string/chomp (mktemp-result :out))
              channel (ssh-sftp session)]
          (assert (zero? (mktemp-result :exit)))
          (sftp channel :put (java.io.ByteArrayInputStream.
                              (.getBytes (str prolog command))) tmpfile)
          (let [chmod-result (ssh session (str "chmod 755 " tmpfile) :return-map true)]
            (if (pos? (chmod-result :exit))
              (error (str "Couldn't chmod script : " ) (chmod-result :err))))
          (let [sudo (if-let [pw (options :sudo-password)]
                       (str "echo \"" (or (user :password) pw) "\" | /usr/bin/sudo -S")
                       "/usr/bin/sudo")
                script-result
                (ssh
                 session
                 (str sudo " ~" (:username user) "/" tmpfile)
                 :return-map true
                 :pty true)]
            (if (zero? (script-result :exit))
              (info (script-result :out))
              (do
                (error (str "Exit status " (script-result :exit)))
                (error (script-result :err))))
            (ssh session (str "rm " tmpfile))
            script-result))))))

(defn sh-script
  "Run a script on local machine."
  [command]
  (let [tmp (java.io.File/createTempFile "pallet" "script")]
    (try
     (copy command tmp)
     (sh "chmod" "+x" (.getPath tmp))
     (let [result (sh "bash" (.getPath tmp) :return-map true)]
       (when (pos? (result :exit))
         (error (str "Command failed: " command "\n" (result :err))))
       (info (result :out))
       result)
     (finally  (.delete tmp)))))
