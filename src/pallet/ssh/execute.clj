(ns pallet.ssh.execute
  "Execution of pallet actions via ssh"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.action.impl :refer [action-symbol]]
   [pallet.actions.decl :refer [context-string]]
   [pallet.common.filesystem :as filesystem]
   [pallet.common.logging.logutils :as logutils]
   [pallet.execute :as execute
    :refer [clean-logs log-script-output result-with-error-map]]
   [pallet.local.execute :as local]
   [pallet.node :as node]
   [pallet.script :refer [with-script-context *script-context*]]
   [pallet.script-builder :as script-builder]
   [pallet.script.lib :as lib]
   [pallet.script.lib :refer [chown env exit mkdir]]
   [pallet.stevedore :as stevedore :refer [fragment]]
   [pallet.transport :as transport]
   [pallet.transport.local]
   [pallet.transport.ssh]
   [pallet.user :refer [obfuscated-passwords]]
   [pallet.utils :refer [log-multiline]]))

(defn authentication
  "Return the user to use for authentication.  This is not necessarily the
  admin user (e.g. when bootstrapping, it is the image user)."
  [user]
  (logging/debugf "authentication %s" (obfuscated-passwords user))
  {:user user})

(defn endpoint
  [target-node]
  (let [proxy (node/proxy target-node)]
    (if proxy
      {:server (or (:host proxy "localhost"))
       :port (:port proxy)}
      {:server (node/node-address target-node)
       :port (node/ssh-port target-node)})))

(defn- ssh-mktemp
  "Create a temporary remote file using the `ssh-session` and the filename
  `prefix`"
  [connection prefix script-env]
  (logging/tracef "ssh-mktemp %s" (bean connection))
  ;; allow default behaviour if no script context available
  (with-script-context (conj *script-context* :dummy)
    (let [cmd (stevedore/script
               ((env ~@(mapcat identity script-env))
                (println
                 (~lib/make-temp-file
                  ~prefix
                  :tmpdir ~(or (get script-env "TMPDIR") true)))))
          result (transport/exec connection {:execv [cmd]} {})]
      (logging/tracef "ssh-mktemp script-env %s" script-env)
      (logging/tracef "ssh-mktemp %s %s" cmd result)
      (if (zero? (:exit result))
        (string/trim (result :out))
        (throw
         (ex-info
          (format "Failed to generate remote temporary file %s" (:err result))
          {:type :remote-execution-failure
           :exit (:exit result)
           :err (:err result)
           :out (:out result)}))))))

(defn get-connection [ssh-connection node user]
  (transport/open
   ssh-connection (endpoint node) (authentication user)
   {:max-tries 3}))

(defn release-connection [ssh-connection node user]
  (transport/release
   ssh-connection (endpoint node) (authentication user)
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
  [ssh-connection node user f]
  (loop [retries 1]
    (let [connection (get-connection ssh-connection node user)
          r (f connection)]
      (if (map? r)
        (cond
         (and (::retriable r) (pos? retries))
         (do
           (release-connection ssh-connection node user)
           (recur (dec retries)))

         (::retriable r) (throw (::exception r))

         :else r)
        r))))

(defmacro with-connection
  "Execute the body with a connection to the current target node,"
  [ssh-connection node user [connection] & body]
  `(with-connection* ~ssh-connection ~node ~user
     (fn [~connection]
       (try
         ~@body
         (catch Exception e#
           (with-connection-exception-handler e#))))))

(defn ssh-script-on-target
  "Execute a bash action on the target via ssh."
  [ssh-connection node user {:keys [context] :as action} [options script]]
  (logging/trace "ssh-script-on-target")
  (logging/trace "action %s options %s" action options)
  (let [endpoint (endpoint node)]
    (logutils/with-context [:target (:server endpoint)]
      (logging/infof
       "%s %s %s %s"
       (:server endpoint) (:port endpoint)
       (or (context-string) "")
       (or (:summary options) ""))
      (with-connection ssh-connection node user [connection]
        (let [authentication (transport/authentication connection)
              script (script-builder/build-script options script action)
              sudo-user (or (:sudo-user action)
                            (-> authentication :user :sudo-user))
              tmpfile (ssh-mktemp
                       connection "pallet" (:script-env action))]

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
                cmd (script-builder/build-code user action tmpfile)
                _ (logging/debugf "ssh-script-on-target command %s" cmd)
                result (transport/exec
                        connection
                        cmd
                        {:output-f (log-script-output
                                    (:server endpoint) (:user authentication))
                         :agent-forwarding (:ssh-agent-forwarding action)
                         :pty (:ssh-pty action true)})
                ;; TODO fix this by putting the flags into the executor
                ;; [result session] (execute/parse-shell-result session result)
                result (update-in result [:out] clean-f)
                result (result-with-error-map
                        (:server endpoint) "Error executing script" result)
                ;; Set the node-value to the result of execution, rather than
                ;; the script.
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
             connection {:execv [(fragment ("rm" -f ~tmpfile))]} {})
            (logging/trace "ssh-script-on-target done")
            (logging/debugf "%s   <== ----------------------------------------"
                            (:server endpoint))
            (when (:new-login-after-action action)
              (transport/close connection))
            result))))))

(defn- ssh-upload
  "Upload a file to a remote location via sftp"
  [user connection file remote-name]
  (logging/infof
   "Transferring file %s from local to %s:%s"
   file (:server (transport/endpoint connection)) remote-name)
  (if-let [dir (.getParent (io/file remote-name))]
    (let [prefix (or (script-builder/prefix :sudo user nil) "")
          {:keys [exit] :as rv} (transport/exec
                                 connection
                                 {:in (stevedore/script
                                       (~prefix (mkdir ~dir :path true))
                                       (~prefix
                                        (chown
                                         ~(-> :username user) ~dir))
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
  [ssh-connection node user value]
  {:pre [node]}
  (logging/debugf "ssh-from-local %s" value)
  (with-connection ssh-connection node user [connection]
    (let [endpoint (transport/endpoint connection)]
      (let [{:keys [local-path remote-path remote-md5-path]} value]
        (logging/debugf
         "Remote local-path %s:%s from %s"
         (:server endpoint) remote-md5-path local-path)
        (let [md5 (try
                    (filesystem/with-temp-file [md5-copy]
                      (transport/receive
                       connection remote-md5-path (.getPath md5-copy))
                      (slurp md5-copy))
                    (catch Exception _ nil))]
          (if md5
            (filesystem/with-temp-file [local-md5-file]
              (logging/debugf "Calculating md5 for %s" local-path)
              (local/local-script
               ((~lib/md5sum ~local-path) ">" ~(.getPath local-md5-file))
               (~lib/normalise-md5 ~(.getPath local-md5-file)))
              (let [local-md5 (slurp local-md5-file)]
                (logging/debugf
                 "md5 check - remote: %s local: %s" md5 local-md5)
                (if (not=
                     (first (string/split md5 #" "))
                     (first (string/split local-md5 #" ")) )
                  (ssh-upload user connection local-path remote-path)
                  (logging/infof
                   "%s:%s is already up to date"
                   (:server endpoint) remote-path))))
            (ssh-upload user connection local-path remote-path))))
      value)))

(defn ssh-to-local
  "Transfer a file from the target machine to the origin via ssh."
  [ssh-connection node user value]
  (with-connection ssh-connection node user [connection]
    (let [{:keys [remote-path local-path]} value]
      (logging/infof
       "Transferring file %s from node to %s" remote-path local-path)
      (transport/receive connection remote-path local-path))
    value))


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
