(ns pallet.utils
  (:use crane.ssh2
        clojure.contrib.logging
        [clojure.contrib.pprint :only [pprint]])
  (:import (org.apache.commons.exec CommandLine
                                    DefaultExecutor
                                    ExecuteWatchdog)))

(defn pprint-lines [s]
  (pprint (seq (.split #"\r?\n" s))))

(defn quoted [s]
  (str "\"" s "\""))

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
   chef."
  ([username password]
     (make-user username password
                (default-public-key-path) (default-private-key-path)))
  ([username password public-key-path private-key-path]
     {:username username
      :password password
      :public-key-path public-key-path
      :private-key-path private-key-path}))

(defn system
  "Launch a system process, return a string containing the process output."
  [cmd]
  (let [command-line (CommandLine/parse cmd)
        executor (DefaultExecutor.)
        watchdog  (ExecuteWatchdog. 180000)]
    (info (str "system " (str command-line)))
    (.setExitValue executor 0)
    (.setWatchdog executor watchdog)
    (.execute executor command-line)))


(defn remote-sudo
  "Run a sudo command on a server."
  [#^java.net.InetAddress server #^String command user]
  (with-connection [connection (session (:private-key-path user) (:username user) (str server))]
    (let [channel (exec-channel connection)
          cmd (str "echo \"" (:password user) "\" | sudo -S " command)]
      (.setErrStream channel System/err true)
      (info (str "sudo! " cmd))
      (with-logs 'pallet
        (let [resp (sh! channel cmd)]
          (when (not (.isClosed channel))
            (try
             (Thread/sleep 1000)
             (catch Exception ee)))
          [resp (.getExitStatus channel)])))))


