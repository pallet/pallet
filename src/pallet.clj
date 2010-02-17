(ns #^{:doc "
Pallet is used to start provisioning a compute node using crane and jclouds.
"}
  pallet
 (:use crane.compute
       crane.ssh2
       clojure.contrib.json.write
       clojure.contrib.pprint)
 (:import org.jclouds.compute.domain.OsFamily
	  org.jclouds.compute.options.TemplateOptions
	  org.jclouds.compute.domain.NodeMetadata
	  (org.apache.commons.exec CommandLine
				   DefaultExecutor
				   ExecuteWatchdog)))

;; this causes swank errors ...
;;(set! *warn-on-reflection* true)

(defn default-private-key-path []
  (str (. System getProperty "user.home") "/.ssh/id_rsa"))
(defn default-public-key-path []
  (str (. System getProperty "user.home") "/.ssh/id_rsa.pub"))


(defn resource-path [name]
  (let [loader (.getContextClassLoader (Thread/currentThread))]
    (. (. loader getResource name) getFile)))

(defn load-resource
  [name]
  (let [loader (.getContextClassLoader (Thread/currentThread))]
    (.getResourceAsStream loader name)))

(defn slurp-resource
  "Reads the resource named by name using the encoding enc into a string
  and returns it."
  ([name] (slurp-resource name (.name (java.nio.charset.Charset/defaultCharset))))
  ([#^String name #^String enc]
     (with-open [r (new java.io.BufferedReader
			(new java.io.InputStreamReader
			     (load-resource name) enc))]
       (let [sb (new StringBuilder)]
	 (loop [c (.read r)]
	   (if (neg? c)
	     (str sb)
	     (do
	       (.append sb (char c))
	       (recur (.read r)))))))))

(defn system
  "Launch a system process, return a string containing the process output."
  [cmd]
  (let [command-line (CommandLine/parse cmd)
	executor (DefaultExecutor.)
	watchdog  (ExecuteWatchdog. 60000)]
    (.setExitValue executor 0)
    (.setWatchdog executor watchdog)
    (.execute executor command-line)))

(defn remote-cmd
  "Run a command on a server."
   ([server command]
      (remote-cmd server command "root"))
   ([server command user]
      (remote-cmd server command user (default-private-key-path)))
   ([#^java.net.InetAddress server #^String command #^String user #^String private-key ]
      (with-connection [connection (session private-key user (str server))]
	(let [channel (shell-channel connection)]
	  (pprint
	   (seq (.split #"\r?\n"
			(sh! channel command))))))))

(defn remote-script
  "Run a command on a server."
   ([server file user]
      (remote-script server file user (default-private-key-path)))
   ([#^java.net.InetAddress server #^java.io.File file #^String user #^String private-key ]
      (with-connection [connection (session private-key user (str server))]
	(put connection file ".")
	(let [channel (exec-channel connection)]
	  (pprint
	   (sh! channel (str "bash ./" (.getName file))))))))



;;; Server Node Configs
(def tag-templates {})

(defmacro with-node-templates
  "Set the template map"
  [map & body]
  `(binding [tag-templates ~map]
     ~@body))

(defn node-template
  "Build the template for specified target node and compute context"
  [compute target public-key-path]
  {:pre [(keyword? target)]}
  (.build ((target tag-templates) compute public-key-path)))

;;; Node utilities
(defn node-list [node-set]
  (map #(.getValue %) node-set))

(defn primary-ip
  "Returns the first public IP for the node."
  [#^NodeMetadata node]
  (let [ips (.toArray (.getPublicAddresses node))]
    (if (pos? (alength ips))
      (.replace (str (aget ips 0)) "/" ""))))

(defn node-by-tag-map [nodes]
  (reduce #(assoc %1
	     (keyword (.getTag %2))
	     (conj %2 (get (keyword (.getTag %2)) %1 [])))))

 ;;; Actions
(defn reboot [compute nodes]
  (dorun (map (partial reboot-node compute) nodes)))

(defn boot-if-down [compute nodes]
  (map (partial reboot-node compute)
       (filter #(= (.getState %) org.jclouds.compute.domain.NodeState/TERMINATED)
	       nodes)))

(defn shutdown-node
  "Shutdown a node."
  [compute node]
  (let [ip (primary-ip node)]
    (if ip
      (remote-cmd ip "shutdown -h 0" "root"))))

(defn shutdown [compute nodes]
  (dorun (map #(shutdown-node compute %) nodes)))

;;; Bootstrapping
(defn md5crypt [passwd]
  (.replace (md5crypt.MD5Crypt/crypt passwd) "$" "\\$"))

(defn write-bootstrap-script-file
  "Write a script to install chef and create the specified user, enabled for
  access with the specified public key."
  ([image-user image-password username password public-key-path]
     (write-bootstrap-script-file
      image-user image-password username password public-key-path
      "http://github.com/hugoduncan/orcloud-cookbooks/tarball/master"))
  ([image-user image-password username password public-key-path bootstrap-repo]
     (let [file (java.io.File/createTempFile "pallet" "init")]
       (with-open [out (java.io.PrintWriter.
			(java.io.BufferedOutputStream.
			 (java.io.FileOutputStream. file)))]
	 (.write out (slurp-resource "server_init.sh"))
	 (.println out)
	 (.write out "cat > ~/conf.json <<EOF")
	 (.println out)
	 (.write out (str "{\"orc\":{\"user\":{\"name\":\"" username"\",\"password\":\""
			  (md5crypt password) "\"},\"sudoers\":[\"" username"\""))
	 (if (and image-user (not (= image-user "root")))
	   (.write out (str ",\"" image-user "\"")))
	 (.write out (str "],\"pk\":\"" (.trim (slurp public-key-path))
			  "\"},\"run_list\":[\"bootstrap-node\"]}"))
	 (.println out)
	 (.write out "EOF")
	 (.println out)
	 ;; github tarballs have a top level dir
	 (.write out "mkdir -p /srv/chef")
	 (.println out)
	 (.write out (str "wget -nv -O- " bootstrap-repo " | tar xvz -C /srv/chef --strip-components 1"))
	 (.println out)
	 (when image-password
	   (.write out (str "echo \"" image-password "\" | sudo -S ")))
	 (.write out (str "chef-solo -c ~/solo.rb -j ~/conf.json"))
	 file))))

(defn bootstrap-node
  "Add a bootstrap initialisation script"
  [node username password public-key-path]
  (let [credentials (.getCredentials node)
	image-user (or (and credentials (.account credentials)) "root")
	image-password (and credentials (.key node))
	image-password (if (and image-password
				(not (.contains image-password "PRIVATE KEY")))
			 image-password)
	file (write-bootstrap-script-file image-user image-password username password public-key-path)]
    (if (primary-ip node)
      (remote-script (primary-ip node) file image-user (default-private-key-path)))))

(defn create-nodes
  "Create a node based on the keyword tag"
  [compute tag count username password public-key-path]
  {:pre [(keyword? tag)]}
  (let [nodes (node-list
	       (run-nodes compute (name tag) count
			  (node-template compute tag public-key-path)))]
    (dorun (map #(bootstrap-node % username password public-key-path) nodes))))


(defn default-user [node]
  (cond
    (.contains (str (.getUri node)) "terremark") "vcloud"
    :else "root"))

;;; Provisioning
(def chef-repo nil)
(def remote-chef-repo "/srv/chef")

(defmacro with-chef-repository [path & body]
  `(binding [chef-repo ~path]
     ~@body))

(defn rsync-repo [from to user]
  (let [cmd (str "/usr/bin/rsync -rP --delete --copy-links -F -F "
		 from  " " user "@" to ":" remote-chef-repo)]
    (println cmd)
    (system cmd)))


(defn rsync-node [node]
  (if (primary-ip node)
    (rsync-repo chef-repo (primary-ip node) (default-user node))))


(defn chef-cook-solo
  "Run a chef solo command on a server.  A command is expected to exist as chef-repo/config/command.json"
   ([#^java.net.InetAddress server #^String command #^String user #^String password #^String private-key]
      (remote-cmd
       (str server)
       (str "cd " remote-chef-repo " && (echo " password " | sudo -S "
	    "chef-solo -c config/solo.rb -j config/" command ".json )")
       user
       private-key)))

(defn chef-cook [node user password private-key]
  (if (primary-ip node)
    (chef-cook-solo (primary-ip node) (.getTag node) user password private-key)))

(defn cook-node [node user password private-key]
  (rsync-node node)
  (chef-cook node user password private-key))


(defn cook-nodes [nodes user password private-key]
  (dorun (map #(cook-node % user password private-key) nodes)))



(defn negative-node-counts [nodes]
  (reduce #(assoc %1
	     (keyword (.getTag %2))
	     (dec (get %1 (keyword (.getTag %2)) 0)))
	  {} nodes))

(defn adjust-node-counts
  "Start or stop the specified number of nodes."
  [compute node-map nodes username password public-key-path]
  (dorun (map #(create-nodes compute (first %) (second %)
			     username password public-key-path)
	      (filter #(pos? (second %)) node-map)))
  ;;    (map #(destroy-nodes compute (first %) (second %) nodes) (filter #(neg? (second %))) node-map)
  (dorun (map #(println "destroy-nodes compute" (first %) (second %) nodes) (filter #(neg? (second %)) node-map))))

(defn converge-node-counts
  "Converge the nodes counts, given a compute facility and a reference number of
   instances."
  [compute node-map username password public-key-path]
  (let [nodes (node-list (nodes compute))]
    (boot-if-down compute nodes) ;; this needs improving - should only reboot if required
    (adjust-node-counts compute
			(merge-with + node-map
				    (negative-node-counts nodes))
			nodes
			username password public-key-path)))

;; We have some options for converging
;; Unneeded nodes can be just shut dowm, or could be destroyed.
;; That means we need to pass some options
(defn converge
  ([compute node-map username password]
     (converge compute node-map username password (default-public-key-path) (default-private-key-path)))
  ([compute node-map username password public-key-path private-key-path]
     (converge-node-counts compute node-map username password public-key-path)
     (cook-nodes (node-list (nodes compute)) username password private-key-path)))


