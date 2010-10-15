(ns pallet.utils
  (:require
   [clojure.contrib.io :as io]
   [clojure.contrib.string :as string]
   [clojure.contrib.pprint :as pprint]
   [clojure.contrib.logging :as logging])
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
  (logging/trace (format "load-resource-url %s" name))
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

(defn resource-properties
  "Returns nil if resource not found."
  [name]
  (let [loader (.getContextClassLoader (Thread/currentThread))
        stream (.getResourceAsStream loader name)]
    (when stream
      (with-open [stream stream]
        (let [properties (new java.util.Properties)]
          (.load properties stream)
          (let [keysseq (enumeration-seq (. properties propertyNames))]
            (reduce (fn [a b] (assoc a b (. properties getProperty b)))
                    {} keysseq)))))))

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

(defn user? [user]
  (instance? pallet.utils.User user))

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

(defmacro with-temp-file [[varname content] & body]
  `(let [~varname (java.io.File/createTempFile "stevedore", ".tmp")]
     (io/copy ~content ~varname)
     (let [rv# (do ~@body)]
       (.delete ~varname)
       rv#)))

(defn tmpfile
  "Create a temporary file"
  ([] (java.io.File/createTempFile "pallet_" "tmp"))
  ([^java.io.File dir] (java.io.File/createTempFile "pallet_" "tmp" dir)))

(defn tmpdir []
  (doto (java.io.File/createTempFile "pallet_" "tmp")
    (.delete)
    (.mkdir)))

(defmacro with-temporary
  [bindings & body]
  {:pre [(vector? bindings)
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
  [m]
  (letfn [(to-symbol [x]
                     (cond
                      (symbol? x) x
                      (string? x) (symbol x)
                      (keyword? x) (symbol (name x))))]
    (zipmap (map to-symbol (keys m)) (vals m))))


(defmacro pipe
  "Build a request processing pipeline from the specified forms"
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
