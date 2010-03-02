(ns pallet.utils
  (:use crane.ssh2
        clojure.contrib.logging
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
  "Launch a system process, return a map containing the exit code, stahdard
  output and standard error of the process."
  [& cmd]
  (apply sh :return-map [:exit :out :err] cmd))

(defmacro with-temp-file [[varname content] & body]
  `(let [~varname (java.io.File/createTempFile "stevedore", ".tmp")]
     (io/copy ~content ~varname)
     (let [rv# (do ~@body)]
       (.delete ~varname)
       rv#)))

(defn bash [cmds]
  (with-temp-file [file cmds]
    (system "/usr/bin/env" "bash" (.getPath file))))


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


