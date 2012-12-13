(ns pallet.ssh.execute
  "Execution of pallet actions via ssh"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.common.filesystem :as filesystem]
   [pallet.common.logging.logutils :as logutils]
   [pallet.execute :as execute]
   [pallet.local.execute :as local]
   [pallet.transport :as transport]
   [pallet.transport.local]
   [pallet.transport.ssh]
   [pallet.node :as node]
   [pallet.script.lib :as lib]
   [pallet.script-builder :as script-builder]
   [pallet.stevedore :as stevedore])
  (:use
   [pallet.action-plan :only [context-label]]
   [pallet.action-impl :only [action-symbol]]
   [slingshot.slingshot :only [throw+]]))

(def ssh-connection (transport/factory :ssh {}))
(def local-connection (transport/factory :local {}))

(defn authentication
  [session]
  {:user (:user session)})

(defn endpoint
  [session]
  (let [target-node (-> session :server :node)]
    {:server (node/node-address target-node)
     :port (node/ssh-port target-node)}))

(defn- ssh-mktemp
  "Create a temporary remote file using the `ssh-session` and the filename
  `prefix`"
  [connection prefix]
  (logging/debugf "ssh-mktemp %s" (.state connection))
  (let [result (transport/exec
                connection
                {:execv [(stevedore/script
                          (println (~lib/make-temp-file ~prefix)))]}
                {})]
    (if (zero? (:exit result))
      (string/trim (result :out))
      (throw+
       {:type :remote-execution-failure
        :message (format
                  "Failed to generate remote temporary file %s" (:err result))
        :exit (:exit result)
        :err (:err result)
        :out (:out result)}))))

(defn get-connection [session]
  (transport/open
   ssh-connection (endpoint session) (authentication session)
   {:max-tries 3}))

(defmacro ^{:indent 2} with-connection
  "Execute the body with a connection to the current target node,"
  [session [connection] & body]
  `(let [session# ~session
         ~connection (get-connection session#)]
     (try
       ~@body
       (catch Exception e#
         (logging/errorf e# "SSH Error")))))

(defn ssh-script-on-target
  "Execute a bash action on the target via ssh."
  [session {:keys [context node-value-path] :as action} action-type
   [options script]]
  (logging/debug "ssh-script-on-target")
  (with-connection session [connection]
    (let [endpoint (transport/endpoint connection)
          authentication (transport/authentication connection)
          script (script-builder/build-script options script action)
          tmpfile (ssh-mktemp connection "pallet")
          sudo-user (or (:sudo-user action)
                        (-> authentication :user :sudo-user))]
      (logutils/with-context [:target (:server endpoint)]
        (logging/infof
         "%s %s %s"
         (:server endpoint) (context-label action)
         (action-symbol (:action action)))
        (logging/debugf "Target %s cmd\n%s via %s as %s"
                        endpoint script tmpfile (or sudo-user "root"))
        (transport/send-text
         connection script tmpfile
         {:mode (if sudo-user 0644 0600)})
        (let [clean-f (comp
                       #(execute/strip-sudo-password % (:user authentication))
                       execute/normalise-eol)
              output-f (comp
                        #(logging/debugf
                          "%s output\n=> %s" (:server endpoint) %)
                        clean-f)
              result (transport/exec
                      connection
                      (script-builder/build-code session action tmpfile)
                      {:output-f output-f})
              [result session] (execute/parse-shell-result session result)
              result (assoc result :script script)
              ;; Set the node-value to the result of execution, rather than
              ;; the script.
              session (assoc-in
                       session [:plan-state :node-values node-value-path]
                       result)]
          (transport/exec
           connection
           {:execv [(stevedore/script (rm -f ~tmpfile))]}
           {})
          [(update-in result [:out] clean-f) session])))))

(defn- ssh-upload
  "Upload a file to a remote location via sftp"
  [connection file remote-name]
  (let [tmpcpy (ssh-mktemp connection "pallet")]
    (logging/infof
     "Transferring %s to %s:%s via %s"
     file (:server (transport/endpoint connection)) remote-name tmpcpy)
    (transport/send-stream connection (io/input-stream file) tmpcpy {})
    (transport/exec
     connection
     {:in (stevedore/script
           (chmod "0600" ~tmpcpy)
           (mv -f ~tmpcpy ~remote-name)
           (exit "$?"))}
     {})))

(defn ssh-from-local
  "Transfer a file from the origin machine to the target via ssh."
  [session value]
  (with-connection session [connection]
    (let [endpoint (transport/endpoint connection)]
      (let [[file remote-name] value
            remote-md5-name (-> remote-name
                                (string/replace #"\.new$" ".md5")
                                (string/replace #"-content$" ".md5"))]
        (logging/debugf
         "Remote file %s:%s from %s" (:server endpoint) remote-md5-name file)
        (let [md5 (try
                    (filesystem/with-temp-file [md5-copy]
                      (transport/receive
                       connection remote-md5-name (.getPath md5-copy))
                      (slurp md5-copy))
                    (catch Exception _ nil))]
          (if md5
            (filesystem/with-temp-file [local-md5-file]
              (logging/debugf "Calculating md5 for %s" file)
              (local/local-script
               ((~lib/md5sum ~file) ">" ~(.getPath local-md5-file))
               (~lib/normalise-md5 ~(.getPath local-md5-file)))
              (let [local-md5 (slurp local-md5-file)]
                (logging/debugf
                 "md5 check - remote: %s local: %s" md5 local-md5)
                (if (not=
                     (first (string/split md5 #" "))
                     (first (string/split local-md5 #" ")) )
                  (ssh-upload connection file remote-name)
                  (logging/infof
                   "%s:%s is already up to date"
                   (:server endpoint) remote-name))))
            (ssh-upload connection file remote-name))))
      [value session])))

(defn ssh-to-local
  "Transfer a file from the origin machine to the target via ssh."
  [session value]
  (with-connection session [connection]
    (let [[remote-file local-file] value]
      (logging/infof
       "Transferring file %s from node to %s" remote-file local-file)
      (transport/receive connection remote-file local-file))
    [value session]))


(defmacro with-ssh-tunnel
  "Execute the body with an ssh-tunnel available for the ports given in the
   tunnels map. Automatically closes port forwards on completion.

   Tunnels should be a map from local ports (integers) to either
     1) An integer remote port. Remote host is assumed to be \"localhost\".
     2) A vector of remote host and remote port. eg, [\"yahoo.com\" 80].

   e.g.
        (with-ssh-tunnel session {2222 22}
           ;; do something on local port 2222
           session)"
  [transport session tunnels & body]
  `(let [{:as connection#} (get-connection ~session)]
     (transport/with-ssh-tunnel
       connection# ~tunnels
       ~@body)))
