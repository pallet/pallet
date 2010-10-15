(ns pallet.compute.jclouds-ssh-test
  (:require
   [clojure.contrib.logging :as logging])
  (:import
   [org.jclouds.ssh SshClient ExecResponse]
   org.jclouds.io.Payload
   org.jclouds.net.IPSocket
   com.google.inject.Module))

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

(defn ssh-test-client*
  "Pass a map of function implementations.  Keys are clojurefied versions of
org.jclouds.ssh.SshClient's methods.  An exec implementation is expected to
return a map with :out, :err and :exit keys"
  [options]
  (fn [socket username password-or-key]
    (reify
      org.jclouds.ssh.SshClient
      (connect [this] (maybe-invoke (:connect options)))
      (disconnect [this] (maybe-invoke (:disconnect options)))
      (exec [this cmd]
            (logging/info (format "ssh cmd: %s" cmd))
            (let [f (:exec options default-exec)
                             response (f cmd)]
                         (ExecResponse.
                          (:out response) (:err response) (:exit response))))
      (get [this path] (maybe-invoke (:get options) path))
      (^void put [this ^String path ^String content]
             (maybe-invoke (:put options) path content))
      ;; (^void put [this ^String path ^org.jclouds.io.Payload content]
      ;;      (maybe-invoke (:put options) path content))
      (getUsername [this] username)
      (getHostAddress [this] (.getAddress socket)))))

(defn ssh-client-factory
  [ctor]
  (reify
   org.jclouds.ssh.SshClient$Factory
   (^org.jclouds.ssh.SshClient create
    [_ ^IPSocket socket ^String username ^String password-or-key]
    (ctor socket username password-or-key))
   (^org.jclouds.ssh.SshClient create
    [_ ^IPSocket socket ^String username ^bytes password-or-key]
    (ctor socket username password-or-key))))

(defn ssh-test-module
  "Create a module that specifies the factory for creating a test service"
  [factory]
  (let [binder (atom nil)]
    (reify
     com.google.inject.Module
     (configure
      [this abinder]
      (reset! binder abinder)
      (.. @binder (bind org.jclouds.ssh.SshClient$Factory)
          (toInstance factory))))))

(defn ssh-test-client
  "Create a module that can be passed to a compute-context, and which implements
an ssh client with the provided map of function implementations.  Keys are
clojurefied versions of org.jclouds.ssh.SshClient's methods"
  [options]
  (ssh-test-module (ssh-client-factory (ssh-test-client* options))))
