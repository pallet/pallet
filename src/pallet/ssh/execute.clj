(ns pallet.ssh.execute
  "Execution of pallet actions via ssh"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.tools.logging :as logging]
   [pallet.common.filesystem :as filesystem]
   [pallet.execute :as execute]
   [pallet.ssh.transport :as transport]
   [pallet.local.execute :as local]
   [pallet.node :as node]
   [pallet.script.lib :as lib]
   [pallet.session :as session]
   [pallet.script-builder :as script-builder]
   [pallet.stevedore :as stevedore]
   [slingshot.core :as slingshot]))

(defn authentication
  [session]
  {:user (session/admin-user session)})

(defn endpoint
  [session]
  (let [target-node (-> session :server :node)]
    {:server (node/node-address target-node)
     :port (node/ssh-port target-node)}))

(defn- ssh-mktemp
  "Create a temporary remote file using the `ssh-session` and the filename
  `prefix`"
  [connection prefix]
  (let [result (transport/exec
                connection
                {:execv [(stevedore/script
                          (println (~lib/make-temp-file ~prefix)))]}
                {})]
    (if (zero? (:exit result))
      (string/trim (result :out))
      (slingshot/throw+
       {:type :remote-execution-failure
        :message (format
                  "Failed to generate remote temporary file %s" (:err result))
        :exit (:exit result)
        :err (:err result)
        :out (:out result)}))))

(defn get-connection [session]
  (transport/open (endpoint session) (authentication session) {}))

(defn ssh-script-on-target
  "Execute a bash action on the target via ssh."
  [session {:keys [f] :as action}]
  (logging/info "ssh-script-on-target")
  (let [{:keys [endpoint authentication] :as connection} (get-connection
                                                          session)
        {:keys [value session]} (f session)
        script (script-builder/build-script value action)
        tmpfile (ssh-mktemp connection "pallet")]
    (logging/infof "Target %s cmd\n%s via %s" endpoint value tmpfile)
    (transport/send-text connection script tmpfile)
    (let [clean-f (comp
                   #(execute/strip-sudo-password % (:user authentication))
                   execute/normalise-eol)
          output-f (comp #(logging/spy %) clean-f)
          result (transport/exec
                  connection
                  (script-builder/build-code session action tmpfile)
                  {:output-f output-f})]
      [(update-in result [:out] clean-f) session])))

(defn- ssh-upload
  "Upload a file to a remote location via sftp"
  [connection file remote-name]
  (let [tmpcpy (ssh-mktemp connection "pallet")]
    (logging/infof
     "Transferring %s to %s:%s via %s"
     file (-> connection :endpoint :server) remote-name tmpcpy)
    (transport/send-stream connection (io/input-stream file) tmpcpy)
    (transport/exec
     connection
     {:in (stevedore/script
           (chmod "0600" ~tmpcpy)
           (mv -f ~tmpcpy ~remote-name)
           (exit "$?"))}
     {})))

(defn ssh-from-local
  "Transfer a file from the origin machine to the target via ssh."
  [session {:keys [f] :as action}]
  (let [{:keys [endpoint authentication] :as connection} (get-connection
                                                          session)
        {:keys [value session]} (f session)]
    (doseq [[file remote-name] value
            :let [remote-md5-name (-> remote-name
                                      (string/replace #"\.new$" ".md5")
                                      (string/replace #"-content$" ".md5"))]]
      (logging/debugf
       "Remote file %s:%s from" (:server endpoint) remote-md5-name file)
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
              (logging/debugf "md5 check - remote: %s local: %s" md5 local-md5)
              (if (not=
                   (first (string/split md5 #" "))
                   (first (string/split local-md5 #" ")) )
                (ssh-upload connection file remote-name)
                (logging/infof
                 "%s:%s is already up to date"
                 (:server endpoint) remote-name))))
          (ssh-upload connection file remote-name))))
    [value session]))

(defn ssh-to-local
  "Transfer a file from the origin machine to the target via ssh."
  [session {:keys [f] :as action}]
  (let [{:keys [endpoint authentication] :as connection} (get-connection
                                                          session)
         {:keys [value session]} (f session)]
    (doseq [[remote-file local-file] value]
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
  `(let [{:keys [endpoint# auth#] :as connection#} (get-connection ~session)]
     (transport/with-ssh-tunnel
       connection# ~tunnels
       ~@body)))
