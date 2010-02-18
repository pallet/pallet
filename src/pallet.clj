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

(defn slurp-as-byte-array
  [#^java.io.File file]
  (let [size (.length file)
	bytes #^bytes (byte-array size)
	stream (new java.io.FileInputStream file)]
    bytes))

(defn system
  "Launch a system process, return a string containing the process output."
  [cmd]
  (let [command-line (CommandLine/parse cmd)
	executor (DefaultExecutor.)
	watchdog  (ExecuteWatchdog. 60000)]
    (println "exec " (str command-line))
    (.setExitValue executor 0)
    (.setWatchdog executor watchdog)
    (.execute executor command-line)))

(defn pprint-lines [s]
  (pprint (seq (.split #"\r?\n" s))))

(defn sudo!
  "Run a sudo command on a server."
  [#^java.net.InetAddress server #^String command #^String user #^String private-key #^String password]
  (with-connection [connection (session private-key user (str server))]
    (let [channel (exec-channel connection)]
      (.setErrStream channel System/err true)
      (let [resp (sh! channel (str "echo \"" password "\" | sudo -S " command))]
	(when (not (.isClosed channel))
	  (try
	   (Thread/sleep 1000)
	   (catch Exception ee)))
	[resp (.getExitStatus channel)]))))


;;; Server Node Configs
(def tag-templates {})

(defmacro with-node-templates
  "Set the template map"
  [map & body]
  `(binding [tag-templates ~map]
     ~@body))

(defn node-template
  "Build the template for specified target node and compute context"
  [compute target public-key-path init-script]
  {:pre [(keyword? target)]}
  (.build
   ((target tag-templates)
    compute public-key-path (.getBytes init-script))))

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

(def bootstrap-repo "http://github.com/hugoduncan/orcloud-cookbooks/tarball/master")

(defn bootstrap-script
  "A script to install chef and create the specified user, enabled for
  access with the specified public key."
  ([user] (bootstrap-script user bootstrap-repo))
  ([user bootstrap-repo]
     (str (slurp-resource "server_init.sh")
	  (apply str (interpose
		      "\n"
		      [ "cat > ~/conf.json <<EOF"
			(str "{\"orc\":{\"user\":{\"name\":\"" (:username user) "\",\"password\":\""
			     (md5crypt (:password user)) "\"},\"sudoers\":[\"" (:username user) "\"],")
			(str "\"pk\":\"" (.trim (slurp (:public-key-path user)))
			     "\"},\"run_list\":[\"bootstrap-node\"]}")
			"EOF"
			"mkdir -p /srv/chef"
			(str "wget -nv -O- " bootstrap-repo " | tar xvz -C /srv/chef --strip-components 1")
			(str "chef-solo -c ~/solo.rb -j ~/conf.json")
			(str "rm -rf /srv/chef/*")])))))


(defn create-nodes
  "Create a node based on the keyword tag"
  [compute tag count user]
  {:pre [(keyword? tag)]}
  (let [script (bootstrap-script user)
	nodes (node-list
	       (run-nodes compute (name tag) count
			  (node-template compute tag (:public-key-path user) script)))]))


(defn default-user [node]
  (cond
    (.contains (str (.getUri node)) "terremark") "vcloud"
    :else "root"))

;;; Provisioning
(def chef-repo nil)
(def remote-chef-repo "/srv/chef/")

(defmacro with-chef-repository [path & body]
  `(binding [chef-repo ~path]
     ~@body))

(defn rsync-repo [from to user]
  (let [cmd (str "/usr/bin/rsync -rP --delete --copy-links -F -F "
		 from  " " (:username user) "@" to ":" remote-chef-repo)]
    (system cmd)))


(defn rsync-node [node user]
  (if (primary-ip node)
    (rsync-repo chef-repo (primary-ip node) user)))

(defn chef-cook-solo
  "Run a chef solo command on a server.  A command is expected to exist as
   chef-repo/config/command.json"
   ([#^java.net.InetAddress server #^String command user]
      (let [[resp status]
	    (sudo!
	     (str server)
	     (str "chef-solo -c " remote-chef-repo "config/solo.rb -j " remote-chef-repo "config/" command ".json")
	     (:username user)
	     (:private-key-path user)
	     (:password user))]
	(pprint-lines resp)
	(if (not (zero? (Integer. status)))
	  (println "CHEF FAILED -------------------------------")))))

(defn chef-cook [node user]
  (if (primary-ip node)
    (chef-cook-solo (primary-ip node) (.getTag node) user)))

(defn cook-node [node user]
  (rsync-node node user)
  (chef-cook node user))

(defn cook-nodes [nodes user]
  (dorun (map #(cook-node % user) nodes)))



(defn negative-node-counts [nodes]
  (reduce #(assoc %1
	     (keyword (.getTag %2))
	     (dec (get %1 (keyword (.getTag %2)) 0)))
	  {} nodes))

(defn adjust-node-counts
  "Start or stop the specified number of nodes."
  [compute node-map nodes user]
  (dorun (map #(create-nodes compute (first %) (second %) user)
	      (filter #(pos? (second %)) node-map)))
  ;;    (map #(destroy-nodes compute (first %) (second %) nodes) (filter #(neg? (second %))) node-map)
  (dorun (map #(println "destroy-nodes compute" (first %) (second %) nodes) (filter #(neg? (second %)) node-map))))

(defn converge-node-counts
  "Converge the nodes counts, given a compute facility and a reference number of
   instances."
  [compute node-map user]
  (let [nodes (node-list (nodes compute))]
    (boot-if-down compute nodes) ;; this needs improving - should only reboot if required
    (adjust-node-counts
     compute
     (merge-with + node-map (negative-node-counts nodes))
     nodes
     user)))

(defn make-user
  ([username password]
     (make-user username password (default-public-key-path) (default-private-key-path)))
  ([username password public-key-path private-key-path]
     {:username username
      :password password
      :public-key-path public-key-path
      :private-key-path private-key-path}))

;; We have some options for converging
;; Unneeded nodes can be just shut dowm, or could be destroyed.
;; That means we need to pass some options
(defn converge
  "Converge the existing compute resources with what is required"
  [compute node-map user]
  (converge-node-counts compute node-map user)
  (cook-nodes (node-list (nodes compute)) user))
