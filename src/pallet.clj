(ns
    #^{:author "Hugo Duncan"
       :doc "
Pallet is used to provision configured compute nodes using crane, jclouds and chef.

It uses a declaritive map for specifying the number of nodes with a given tag.
Each tag is used to look up a machine image template specification (in crane and
jsclouds), and to lookup configuration information (in chef).  The converge
function then tries to bring you compute servers into alignment with your
declared counts and configurations.

The bootstrap process for new compute nodes installs a user with sudo
permissions, using the specified username and password. The installed user is
used to execute the chef cookbooks.

Once the nodes are bootstrapped, and fall all existing nodes
the configured node information is written to the \"compute-nodes\" cookbook
before chef is run, and this provides a :compute_nodes attribute.  The
compute-nodes cookbook is expected to exist in the site-cookbooks of the
chef-repository you specify with `with-chef-repository`.

`chef-solo` is then run with chef repository you have specified using the node
tag as a configuration target.
"}
  pallet
  (:use crane.compute
        crane.ssh2
        clojure.contrib.logging
        [clojure.contrib.pprint :only [pprint]]
        [clojure.contrib.java-utils :only [file]]
        [clojure.contrib.duck-streams :only [with-out-writer]])
  (:import org.jclouds.compute.domain.OsFamily
           org.jclouds.compute.options.TemplateOptions
           org.jclouds.compute.domain.NodeMetadata
           (org.apache.commons.exec CommandLine
                                    DefaultExecutor
                                    ExecuteWatchdog)))

(def *chef-repository* nil)
(defmacro with-chef-repository
  "Specifies the chef repository that contains the cookboks and configurations"
  [path & body]
  `(binding [*chef-repository* ~path]
     ~@body))


(def *remote-chef-path* "/srv/chef/")
(defmacro with-remote-chef-path
  "Specifies the path to use for the chef cookbooks on the remote machine."
  [path & body]
    `(binding [*remote-chef-path* ~path]
     ~@body))

(def *bootstrap-repo* "http://github.com/hugoduncan/orcloud-cookbooks/tarball/master")
(defmacro with-bootstrap-repo
  "Define the url used to retrieve the bootstrap repository."
  [url & body]
  `(binding [*bootstrap-repo* url]
     ~@body))


(defn default-private-key-path []
  (str (. System getProperty "user.home") "/.ssh/id_rsa"))
(defn default-public-key-path []
  (str (. System getProperty "user.home") "/.ssh/id_rsa.pub"))

(defn make-user
  "Create a description of the admin user to be created and used for running
   chef."
  ([username password]
     (make-user username password
                (default-public-key-path) (default-private-key-path)))
  ([username password public-key-path private-key-path]
     {:username username
      :password password
      :public-key-path public-key-path
      :private-key-path private-key-path}))

(def *admin-user* (make-user "admin" "dontuseMe123"))
(defmacro with-admin-user
  "Specify the admin user for running remote commands.  The user is specified
  either as a user map, which can be created with make-user, or as an argument
  list that will be passed to make-user."
  [user & exprs]
  `(binding [*admin-user* (if (vector? user) (apply make-user user) user)]
     ~@exprs))



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
        watchdog  (ExecuteWatchdog. 180000)]
    (info (str "system " (str command-line)))
    (.setExitValue executor 0)
    (.setWatchdog executor watchdog)
    (.execute executor command-line)))

(defn pprint-lines [s]
  (pprint (seq (.split #"\r?\n" s))))

(defn sudo!
  "Run a sudo command on a server."
  [#^java.net.InetAddress server #^String command user]
  (with-connection [connection (session (:private-key-path user) (:username user) (str server))]
    (let [channel (exec-channel connection)
          cmd (str "echo \"" (:password user) "\" | sudo -S " command)]
      (.setErrStream channel System/err true)
      (info (str "sudo! " cmd))
      (with-logs 'pallet
        (let [resp (sh! channel cmd)]
          (when (not (.isClosed channel))
            (try
             (Thread/sleep 1000)
             (catch Exception ee)))
          [resp (.getExitStatus channel)])))))


;;; Server Node Configs
(def *node-templates* {})

(defmacro with-node-templates
  "Set the template map"
  [map & body]
  `(binding [*node-templates* ~map]
     ~@body))

(defn node-template
  "Build the template for specified target node and compute context"
  ([compute target public-key-path init-script]
     (node-template compute target public-key-path init-script *node-templates*))
  ([compute target public-key-path init-script node-templates]
     {:pre [(keyword? target)]}
     (info (str "building node template for " target))
     (let [add-if-none (fn [options keyword arg]
                         (if (not-any? #(= keyword %) options)
                           (apply vector keyword arg options)
                           options))
           options (add-if-none
                    (add-if-none (target node-templates)
                                 :authorize-public-key (slurp public-key-path))
                    :run-script (.getBytes init-script))]
       (apply crane.compute/build-template compute options))))

;;; Node utilities
(defn primary-ip
  "Returns the first public IP for the node."
  [#^NodeMetadata node]
  (first (public-ips node)))

(defn nodes-by-tag-map [nodes]
  (reduce #(assoc %1
             (keyword (tag %2))
             (conj (get %1 (keyword (tag %2)) []) %2)) {} nodes))

 ;;; Actions
(defn reboot [compute nodes]
  (dorun (map (partial reboot-node compute) nodes)))

(defn boot-if-down [compute nodes]
  (map (partial reboot-node compute)
       (filter terminated? nodes)))

(defn shutdown-node
  "Shutdown a node."
  [compute user node]
  (let [ip (primary-ip node)]
    (if ip
      (sudo! ip "shutdown -h 0" user))))

(defn shutdown [compute nodes]
  (dorun (map #(shutdown-node compute %) nodes)))

;;; Bootstrapping
(defn md5crypt [passwd]
  (.replace (md5crypt.MD5Crypt/crypt passwd) "$" "\\$"))

(defn bootstrap-script
  "A script to install chef and create the specified user, enabled for
  access with the specified public key."
  ([] (bootstrap-script *admin-user* *bootstrap-repo*))
  ([user] (bootstrap-script user *bootstrap-repo*))
  ([user bootstrap-url]
     (str (slurp-resource "server_init.sh")
          (apply str (interpose
                      "\n"
                      [ "cat > ~/conf.json <<EOF"
                        (str "{\"orc\":{\"user\":{\"name\":\"" (:username user) "\",\"password\":\""
                             (md5crypt (:password user)) "\"},\"sudoers\":[\"" (:username user) "\"],")
                        (str "\"pk\":\"" (.trim (slurp (:public-key-path user)))
                             "\"},\"run_list\":[\"bootstrap-node\"]}")
                        "EOF"
                        (str "mkdir -p " *remote-chef-path*)
                        (str "wget -nv -O- " bootstrap-url " | tar xvz -C " *remote-chef-path* " --strip-components 1")
                        (str "chef-solo -c ~/solo.rb -j ~/conf.json")
                        (str "rm -rf " *remote-chef-path* "/*")]))))) ;; prevent permission issues


(defn create-nodes
  "Create count nodes based on the template for tag."
  ([compute tag count user]
     (create-nodes compute tag count user *bootstrap-repo*))
  ([compute tag count user bootstrap-repo]
     {:pre [(keyword? tag)]}
     (info (str "Starting " count " nodes for " tag))
     (run-nodes compute (name tag) count
                (node-template compute tag (:public-key-path user)
                               (bootstrap-script user bootstrap-repo)))))


;;; Provisioning
(defn rsync-repo [from to user]
  (info (str "rsyncing chef repository to " to))
  (let [cmd (str "/usr/bin/rsync -rP --delete --copy-links -F -F "
                 from  " " (:username user) "@" to ":" *remote-chef-path*)]
    (system cmd)))


(defn rsync-node [node user]
  (if (primary-ip node)
    (rsync-repo *chef-repository* (primary-ip node) user)))

;;; Chef recipe attribute output
(defn quoted [s]
  (str "\"" s "\""))

(defn ips-to-rb [nodes]
  (letfn [(output-node [node]
                       (str "{"
                            " :name => " (quoted (hostname node))
                            ",:public_ips => [" (apply str (interpose "," (map quoted (public-ips node)))) "]"
                            ",:private_ips => [" (apply str (interpose "," (map quoted (private-ips node)))) "]"
                            "}"))
          (output-tag [[tag nodes]]
                      (str "set[:compute_nodes][" tag "] = ["
                           (apply str (interpose "," (map output-node nodes)))
                           "]" ))]
    (apply str (interpose "\n" (map output-tag (nodes-by-tag-map nodes))))))

(def node-cookbook "compute-nodes")

(defn output-attributes [nodes]
  (with-out-writer  (file *chef-repository* "site-cookbooks" node-cookbook
                          "attributes" (str node-cookbook ".rb"))
    (println (ips-to-rb nodes))))

;;; Chef invocation
(defn chef-cook-solo
  "Run a chef solo command on a server.  A command is expected to exist as
   chef-repo/config/command.json"
  ([server command]
     (chef-cook-solo server command *admin-user* *remote-chef-path*))
  ([server command user]
     (chef-cook-solo server command user *remote-chef-path*))
  ([server command user remote-chef-path]
     (info (str "chef-cook-solo " server))
     (let [[resp status]
            (sudo!
             (str server)
             (str "chef-solo -c " remote-chef-path "config/solo.rb -j "
                  remote-chef-path "config/" command ".json")
             user)]
        (pprint-lines resp)
        (if (not (zero? (Integer. status)))
          (println "CHEF FAILED -------------------------------")))))

(defn chef-cook [node user]
  (if (primary-ip node)
    (chef-cook-solo (primary-ip node) (tag node) user)))

(defn cook-node
  "Run chef on the specified node."
  [node user]
  (rsync-node node user)
  (chef-cook node user))

(defn cook-nodes [nodes user]
  (dorun (map #(cook-node % user) nodes)))

(defn negative-node-counts [nodes]
  (reduce #(assoc %1
             (keyword (tag %2))
             (dec (get %1 (keyword (tag %2)) 0)))
          {} nodes))

(defn node-has-tag? [tag node]
  (= (name tag) (node-tag node)))

(defn destroy-nodes-with-count [compute nodes tag count]
  (info (str "destroying " count " nodes with tag " tag))
  (dorun (map (partial destroy-node compute)
              (take count (filter (partial node-has-tag? tag) nodes)))))

(defn adjust-node-counts
  "Start or stop the specified number of nodes."
  ([compute node-map nodes user]
     (adjust-node-counts compute node-map nodes user *bootstrap-repo*))
  ([compute node-map nodes user bootstrap-repo]
     (dorun (map #(create-nodes compute (first %) (second %) user bootstrap-repo)
                 (filter #(pos? (second %)) node-map)))
     (info (str "adjust-node-counts finished starting nodes"))
     (dorun (map #(destroy-nodes-with-count compute nodes (first %) (- (second %)))
                 (filter #(neg? (second %)) node-map)))
     ;; (dorun (map #(println "destroy-nodes compute" (first %) (second %) nodes) (filter #(neg? (second %)) node-map)))
     (info (str "adjust-node-counts finished destroying nodes"))))

(defn converge-node-counts
  "Converge the nodes counts, given a compute facility and a reference number of
   instances."
  ([compute node-map user]
     (converge-node-counts compute node-map user *bootstrap-repo*))
  ([compute node-map user bootstrap-repo]
     (let [nodes (nodes compute)]
       (boot-if-down compute nodes) ;; this needs improving - should only reboot if required
       (adjust-node-counts
        compute
        (merge-with + node-map (negative-node-counts nodes))
        nodes user bootstrap-repo))))



;; We have some options for converging
;; Unneeded nodes can be just shut dowm, or could be destroyed.
;; That means we need to pass some options
(defn converge
  "Converge the existing compute resources with what is specified in node-map.
Returns a sequence containing the node metadata for all nodes."
  ([compute node-map]
     (converge compute node-map *admin-user* *bootstrap-repo*))
  ([compute node-map user]
     (converge compute node-map user *bootstrap-repo*))
  ([compute node-map user bootstrap-repo]
     (converge-node-counts compute node-map user bootstrap-repo)
     (let [nodes (nodes compute)]
       (output-attributes nodes)
       (cook-nodes nodes user)
       nodes)))

