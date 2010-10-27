(ns pallet.compute.jclouds-ssh-test
  (:require
   [clojure.contrib.logging :as logging])
  (:import
   [org.jclouds.ssh SshClient ExecResponse]
   org.jclouds.domain.Credentials
   org.jclouds.io.Payload
   org.jclouds.net.IPSocket))


(defn instantiate [impl-class & args]
  (let [constructor (first
                     (filter
                      (fn [c] (= (count args) (count (.getParameterTypes c))))
                      (.getDeclaredConstructors impl-class)))]
    (.newInstance impl-class (object-array args))))




;; define an instance or implementation of the following interfaces:

(defn maybe-invoke [f & args]
  (when f
    (apply f args)))

(defn default-exec
  "Default exec function - replies to ./runscript status by returning 1"
  [cmd]
  (merge
   {:exit 0 :err "stderr" :out "stdout"}
   (condp = cmd
       "./bootstrap status" {:exit 1 :out "[]"}
       {})))

(deftype NoOpClient
    [socket username password]
  SshClient
  (connect [this])
  (disconnect [this])
  (exec [this cmd]
        (logging/info (format "ssh cmd: %s" cmd))
        (let [response (default-exec cmd)]
          (ExecResponse. (:out response) (:err response) (:exit response))))
  (get [this path] )
  (^void put [this ^String path ^String content])
  (^void put [this ^String path ^org.jclouds.io.Payload content])
  (getUsername [this] username)
  (getHostAddress [this] (.getAddress socket)) )

(defn no-op-ssh-client
  [socket username password]
  (NoOpClient. socket username password))


(deftype SshClientFactory
    [factory-fn]
  org.jclouds.ssh.SshClient$Factory
  (^org.jclouds.ssh.SshClient
   create
   [_ ^IPSocket socket ^String username ^String password-or-key]
   (factory-fn socket username password-or-key))
  (^org.jclouds.ssh.SshClient
   create
   [_ ^IPSocket socket ^String username ^bytes password-or-key]
   (factory-fn socket username password-or-key))
    (^org.jclouds.ssh.SshClient
   create
   [_ ^IPSocket socket ^Credentials credentials]
   (factory-fn socket (.identity credentials) (.credential credentials))))

(deftype Module
    [factory binder]
  com.google.inject.Module
  (configure
   [this abinder]
   (reset! binder abinder)
   (.. @binder (bind org.jclouds.ssh.SshClient$Factory)
       (toInstance factory))))

(defn ssh-test-module
  "Create a module that specifies the factory for creating a test service"
  [factory]
  (let [binder (atom nil)]
    (Module. factory binder)))

(defn ssh-test-client
  "Create a module that can be passed to a compute-context, and which implements
an ssh client with the provided map of function implementations.  Keys are
clojurefied versions of org.jclouds.ssh.SshClient's methods"
  [factory-fn]
  (ssh-test-module (SshClientFactory. factory-fn)))
