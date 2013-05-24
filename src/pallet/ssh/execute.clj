(ns pallet.ssh.execute
  "Execution of pallet actions via ssh"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.action-impl :refer [action-symbol]]
   [pallet.action-plan :refer [context-label]]
   [pallet.common.filesystem :as filesystem]
   [pallet.common.logging.logutils :as logutils]
   [pallet.execute :as execute
    :refer [clean-logs log-script-output result-with-error-map]]
   [pallet.local.execute :as local]
   [pallet.node :as node]
   [pallet.script-builder :as script-builder]
   [pallet.script.lib :as lib]
   [pallet.script.lib :refer [chown exit mkdir]]
   [pallet.stevedore :as stevedore]
   [pallet.transport :as transport]
   [pallet.transport.local]
   [pallet.transport.ssh]
   [pallet.utils :refer [log-multiline]]))

(def ssh-connection (transport/factory :ssh {}))
(def local-connection (transport/factory :local {}))

(defn authentication
  [session]
  {:user (:user session)})

(defn endpoint
  [session]
  (let [target-node (-> session :server :node)
        proxy (node/proxy target-node)]
    (if proxy
      {:server (or (:host proxy "localhost"))
       :port (:port proxy)}
      {:server (node/node-address target-node)
       :port (node/ssh-port target-node)})))

(defn- ssh-mktemp
  "Create a temporary remote file using the `ssh-session` and the filename
  `prefix`"
  [connection prefix]
  (logging/tracef "ssh-mktemp %s" (bean connection))
  (let [result (transport/exec
                connection
                {:execv [(stevedore/script
                          (println (~lib/make-temp-file ~prefix)))]}
                {})]
    (if (zero? (:exit result))
      (string/trim (result :out))
      (throw
       (ex-info
        (format "Failed to generate remote temporary file %s" (:err result))
        {:type :remote-execution-failure
         :exit (:exit result)
         :err (:err result)
         :out (:out result)})))))

(defn get-connection [session]
  (transport/open
   ssh-connection (endpoint session) (authentication session)
   {:max-tries 3}))

(defn release-connection [session]
  (transport/release
   ssh-connection (endpoint session) (authentication session)
   {:max-tries 3}))

(defn ^{:internal true} with-connection-exception-handler
  [e]
  (logging/errorf e "SSH Error")
  (if-let [{:keys [type reason]} (ex-data e)]
    (if (and (= type :clj-ssh/open-channel-failure)
             (= reason :clj-ssh/channel-open-failed))
      {::retriable true ::exception e}
      (throw e))
    (throw e)))

(defn ^{:internal true} with-connection*
  "Execute a function with a connection to the current target node,"
  [session f]
  (loop [retries 1]
    (let [connection (get-connection session)
          r (f connection)]
      (if (map? r)
        (cond
         (and (::retriable r) (pos? retries))
         (do
           (release-connection session)
           (recur (dec retries)))

         (::retriable r) (throw (::exception r))

         :else r)
        r))))

(defmacro ^{:indent 2} with-connection
  "Execute the body with a connection to the current target node,"
  [session [connection] & body]
  `(with-connection* ~session (fn [~connection]
                                (try
                                  ~@body
                                  (catch Exception e#
                                    (with-connection-exception-handler e#))))))

(defn ssh-script-on-target
  "Execute a bash action on the target via ssh."
  [session {:keys [context node-value-path] :as action} action-type
   [options script]]
  (logging/trace "ssh-script-on-target")
  (logging/trace "action %s options %s" action options)
  (let [endpoint (endpoint session)]
    (logutils/with-context [:target (:server endpoint)]
      (logging/infof
       "%s %s %s"
       (:server endpoint)
       (or (context-label action) "")
       (or (:summary options) ""))
      (with-connection session [connection]
        (let [authentication (transport/authentication connection)
              script (script-builder/build-script options script action)
              tmpfile (ssh-mktemp connection "pallet")
              sudo-user (or (:sudo-user action)
                            (-> authentication :user :sudo-user))]

          (log-multiline :debug (str (:server endpoint) " ==> %s")
                         (str " -----------------------------------------\n"
                              script
                              "\n------------------------------------------"))
          (logging/debugf
           "%s:%s send script via %s as %s"
           (:server endpoint) (:port endpoint) tmpfile (or sudo-user "root"))
          (logging/debugf "%s   <== ----------------------------------------"
                          (:server endpoint))
          (transport/send-text
           connection script tmpfile
           {:mode (if sudo-user 0644 0600)})
          (logging/trace "ssh-script-on-target execute script file")
          (let [clean-f (clean-logs (:user authentication))
                result (transport/exec
                        connection
                        (script-builder/build-code session action tmpfile)
                        {:output-f (log-script-output
                                    (:server endpoint) (:user authentication))
                         :agent-forwarding (:ssh-agent-forwarding action)})
                [result session] (execute/parse-shell-result session result)
                result (update-in result [:out] clean-f)
                result (result-with-error-map
                        (:server endpoint) "Error executing script" result)
                ;; Set the node-value to the result of execution, rather than
                ;; the script.
                session (assoc-in
                         session [:plan-state :node-values node-value-path]
                         result)
                result (assoc result
                         :script (if (and (sequential? script)
                                          (map? (first script)))
                                   (update-in script [0] dissoc :summary)
                                   script)
                         :summary (when (and (sequential? script)
                                             (map? (first script)))
                                    (:summary options)))]
            (logging/trace "ssh-script-on-target remove script file")
            (transport/exec
             connection
             {:execv [(stevedore/script ("rm" -f ~tmpfile))]}
             {})
            (logging/trace "ssh-script-on-target done")
            (logging/debugf "%s   <== ----------------------------------------"
                            (:server endpoint))
            (when (:new-login-after-action action)
              (transport/close connection))
            [result session]))))))

(defn- ssh-upload
  "Upload a file to a remote location via sftp"
  [session connection file remote-name]
  (logging/infof
   "Transferring file %s from local to %s:%s"
   file (:server (transport/endpoint connection)) remote-name)
  (if-let [dir (.getParent (io/file remote-name))]
    (let [prefix (or (script-builder/prefix :sudo session nil) "")
          {:keys [exit] :as rv} (transport/exec
                                 connection
                                 {:in (stevedore/script
                                       (~prefix (mkdir ~dir :path true))
                                       (~prefix
                                        (chown
                                         ~(-> session :user :username) ~dir))
                                       (exit "$?"))}
                                 {})]
      (if (zero? exit)
        (transport/send-stream
         connection (io/input-stream file) remote-name {:mode 0600})
        (throw (ex-info
                (str "Failed to create target directory " dir)
                rv))))
    (transport/send-stream
     connection (io/input-stream file) remote-name {:mode 0600})))

(defn ssh-from-local
  "Transfer a file from the origin machine to the target via ssh."
  [session value]
  (logging/tracef "ssh-from-local %s" value)
  (logging/tracef "ssh-from-local %s" session)
  (assert (-> session :server) "Target server in session")
  (assert (-> session :server :node) "Target node in session")
  (with-connection session [connection]
    (let [endpoint (transport/endpoint connection)]
      (let [[file remote-name remote-md5-name] value]
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
                  (ssh-upload session connection file remote-name)
                  (logging/infof
                   "%s:%s is already up to date"
                   (:server endpoint) remote-name))))
            (ssh-upload session connection file remote-name))))
      [value session])))

(defn ssh-to-local
  "Transfer a file from the target machine to the origin via ssh."
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
