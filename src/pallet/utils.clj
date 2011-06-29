(ns pallet.utils
  "Utilities used across pallet."
  (:require
   [clojure.java.io :as io]
   [clojure.contrib.jar :as jar]
   [clojure.contrib.string :as string]
   [clojure.contrib.pprint :as pprint]
   [clojure.tools.logging :as logging])
  (:use
   clojure.tools.logging
   clojure.contrib.def)
  (:import
   (java.security
    NoSuchAlgorithmException
    MessageDigest)
   (org.apache.commons.codec.binary Base64)))

(defn pprint-lines
  "Pretty print a multiline string"
  [s]
  (pprint/pprint (seq (.split #"\r?\n" s))))

(defn quoted
  "Return the string value of the argument in quotes."
  [s]
  (str "\"" s "\""))

(defn underscore [s]
  "Change - to _"
  (apply str (interpose "_"  (.split s "-"))))

(defn as-string
  "Return the string value of the argument."
  [arg]
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

(defn load-resource-url
  [name]
  (logging/tracef "load-resource-url %s" name)
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

(defn resource-properties
  "Given a resource `path`, load it as a java properties file.
   Returns nil if resource not found."
  [path]
  (let [loader (.getContextClassLoader (Thread/currentThread))
        stream (.getResourceAsStream loader path)]
    (when stream
      (with-open [stream stream]
        (let [properties (new java.util.Properties)]
          (.load properties stream)
          (let [keysseq (enumeration-seq (. properties propertyNames))]
            (reduce (fn [a b] (assoc a b (. properties getProperty b)))
                    {} keysseq)))))))

(defn slurp-as-byte-array
  "Read the given file as a byte array."
  [#^java.io.File file]
  (let [size (.length file)
        bytes #^bytes (byte-array size)
        stream (new java.io.FileInputStream file)]
    bytes))

(defn find-var-with-require
  "Find the var for the given namespace and symbol. If the namespace does
   not exist, then it will be required.
       (find-var-with-require 'my.ns 'a-symbol)
       (find-var-with-require 'my.ns/a-symbol)"
  ([sym]
     (find-var-with-require (symbol (namespace sym)) (symbol (name sym))))
  ([ns sym]
     (try
       (when-not (find-ns ns)
         (require ns))
       (when-let [v (ns-resolve ns sym)]
         (var-get v))
       (catch Exception _))))

(defn default-private-key-path
  "Return the default private key path."
  []
  (str (System/getProperty "user.home") "/.ssh/id_rsa"))

(defn default-public-key-path
  "Return the default public key path"
  []
  (str (System/getProperty "user.home") "/.ssh/id_rsa.pub"))

(defrecord User
  [username public-key-path private-key-path passphrase
   password sudo-password no-sudo])

(defn user? [user]
  (instance? pallet.utils.User user))

(defn make-user
  "Creates a User record with the given username and options. Generally used
   in conjunction with *admin-user* and pallet.core/with-admin-user, or passed
   to `lift` or `converge` as the named :user argument.

   Options:
    - :public-key-path (defaults to ~/.ssh/id_rsa.pub)
    - :private-key-path (defaults to ~/.ssh/id_rsa)
    - :passphrase
    - :password
    - :sudo-password (defaults to :password)
    - :no-sudo"
  [username & {:keys [public-key-path private-key-path passphrase
                      password sudo-password no-sudo] :as options}]
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
   property.  If not specified then the user.name property is used.
   The admin user can also be specified in config.clj when running tasks
   from the command line.")

(defn admin-user-from-config-var
  "Set the admin user based on pallet.config setup."
  []
  (find-var-with-require 'pallet.config 'admin-user))

(defn admin-user-from-config
  "Set the admin user based on a config map"
  [config]
  (when-let [admin-user (:admin-user config)]
    (apply make-user (:username admin-user) (apply concat admin-user))))

(defmacro with-temp-file
  "Create a block where `varname` is a temporary `File` containing `content`."
  [[varname content] & body]
  `(let [~varname (java.io.File/createTempFile "stevedore", ".tmp")]
     (io/copy ~content ~varname)
     (let [rv# (do ~@body)]
       (.delete ~varname)
       rv#)))

(defn tmpfile
  "Create a temporary file"
  ([] (java.io.File/createTempFile "pallet_" "tmp"))
  ([^java.io.File dir] (java.io.File/createTempFile "pallet_" "tmp" dir)))

(defn tmpdir
  "Create a temporary directory."
  []
  (doto (java.io.File/createTempFile "pallet_" "tmp")
    (.delete) ; this is a potential cause of non-unique names
    (.mkdir)))

(defmacro with-temporary
  "A block scope allowing multiple bindings to expressions.  Each binding will
   have the member function `delete` called on it."
  [bindings & body] {:pre
   [(vector?  bindings)
         (even? (count bindings))]}
  (cond
   (= (count bindings) 0) `(do ~@body)
   (symbol? (bindings 0)) `(let ~(subvec bindings 0 2)
                             (try
                              (with-temporary ~(subvec bindings 2) ~@body)
                              (finally
                               (. ~(bindings 0) delete))))
   :else (throw (IllegalArgumentException.
                 "with-temporary only allows Symbols in bindings"))))



(defn map-with-keys-as-symbols
  "Produce a map that is the same as m, but with all keys are converted to
  symbols."
  [m]
  (letfn [(to-symbol [x]
                     (cond
                      (symbol? x) x
                      (string? x) (symbol x)
                      (keyword? x) (symbol (name x))))]
    (zipmap (map to-symbol (keys m)) (vals m))))

(defn dissoc-keys
  "Like clojure.core/dissoc, except it takes a vector of keys to remove"
  [m keys]
  (apply dissoc m keys))

(defn dissoc-if-empty
  "Like clojure.core/dissoc, except it only dissoc's if the value at the
   keyword is nil."
  [m key]
  (if (empty? (m key)) (dissoc m key) m))

(defn maybe-update-in
  "'Updates' a value in a nested associative structure, where ks is a
  sequence of keys and f is a function that will take the old value
  and any supplied args and return the new value, and returns a new
  nested structure.  If any levels do not exist, hash-maps will be
  created only if the update function returns a non-nil value. If
  the update function returns nil, the map is returned unmodified."
  ([m [& ks] f & args]
     (let [v (f (get-in m ks))]
       (if v
         (assoc-in m ks v)
         m))))

(defmacro pipe
  "Build a session processing pipeline from the specified forms."
  [& forms]
  (let [[middlewares etc] (split-with #(or (seq? %) (symbol? %)) forms)
        middlewares (reverse middlewares)
        [middlewares [x :as etc]]
          (if (seq etc)
            [middlewares etc]
            [(rest middlewares) (list (first middlewares))])
          handler x]
    (if (seq middlewares)
      `(-> ~handler ~@middlewares)
      handler)))

(defn base64-md5
  "Computes the base64 encoding of the md5 of a string"
  [#^String unsafe-id]
  (let [alg (doto (MessageDigest/getInstance "MD5")
              (.reset)
              (.update (.getBytes unsafe-id)))]
    (try
      (Base64/encodeBase64URLSafeString (.digest alg))
      (catch NoSuchAlgorithmException e
        (throw (new RuntimeException e))))))

(defmacro middleware
  "Build a middleware processing pipeline from the specified forms.
   The result is a middleware."
  [& forms]
  (let [[middlewares] (split-with #(or (seq? %) (symbol? %)) forms)
        middlewares (reverse middlewares)]
    (if (seq middlewares)
      `(fn [handler#] (-> handler# ~@middlewares))
      `(fn [handler#] handler#))))

;; see http://weblogs.java.net/blog/kohsuke/archive/2007/04/how_to_convert.html
(defn file-for-url
  "Convert a URL to a File. "
  [^java.net.URL url]
  (try
    (java.io.File. (.toURI url))
    (catch java.net.URISyntaxException _
      (java.io.File. (.getPath url)))))

(defn classpath-urls
  "Return the classpath URL's for the current clojure classloader."
  []
  (.getURLs (.getClassLoader clojure.lang.RT)))

(defn classpath
  "Return the classpath File's for the current clojure classloader."
  []
  (map file-for-url (classpath-urls)))

(defn classpath-jarfiles
  "Returns a sequence of JarFile objects for the JAR files on classpath."
  []
  (filter
   identity
   (map
    #(try
       (java.util.jar.JarFile. %)
       (catch Exception _
         (logging/warnf "Unable to open jar file on classpath: %s" %)))
    (filter jar/jar-file? (classpath)))))

(defmacro forward-to-script-lib
  "Forward a script to the new script lib"
  [& symbols]
  `(do
     ~@(for [sym symbols]
         (list `def sym (symbol "pallet.script.lib" (name sym))))))
