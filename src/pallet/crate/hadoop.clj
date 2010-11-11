(ns pallet.crate.hadoop
  "Install hadoop"
  (:require
   [pallet.parameter :as parameter]
   [pallet.stevedore :as stevedore]
   [pallet.request-map :as request-map]
   [pallet.resource.directory :as directory]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.file :as file]
   [pallet.resource.remote-directory :as remote-directory]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.user :as user]
   [pallet.resource.filesystem-layout :as filesystem-layout]
   [pallet.script :as script]
   [clojure.contrib.prxml :as prxml]))

(def install-path "/usr/local/hadoop")
(def log-path "/var/log/hadoop")
(def tx-log-path (format "%s/txlog" log-path))
(def config-path "/etc/hadoop")
(def data-path "/var/hadoop")
(def hadoop-home install-path)
(def hadoop-user "hadoop")
(def hadoop-group "hadoop")

(defn url "Download url"
  [version]
  (format
   "http://www.apache.org/dist/hadoop/core/hadoop-%s/hadoop-%s.tar.gz"
   version version))

(defn install
  "Install Hadoop"
  [request & {:keys [user group version home]
              :or {user hadoop-user
                   group hadoop-group
                   version "3.3.1"}
              :as options}]
  (let [url (url version)
        home (or home (format "%s-%s" install-path version))
        config-dir (str home "/conf")
        etc-config-dir (stevedore/script (str (config-root) "/hadoop"))
        pid-dir (stevedore/script (str (pid-root) "/hadoop"))
        log-dir (stevedore/script (str (log-root) "/hadoop"))
        data-dir "/data"]
    (->
     request
     (parameter/assoc-for-target
      [:hadoop :home] home
      [:hadoop :owner] user
      [:hadoop :group] group
      [:hadoop :config-dir] config-dir
      [:hadoop :data-dir] data-dir
      [:hadoop :pid-dir] pid-dir)
     (user/user user :system true)
     (user/group group :system true)
     (remote-directory/remote-directory
      home
      :url url :md5-url (str url ".md5")
      :unpack :tar :tar-options "xz"
      :owner user :group group)
     (directory/directory log-path :owner user :group group :mode "0755")
     (directory/directory config-dir :owner user :group group :mode "0755")
     (directory/directory data-dir :owner user :group group :mode "0755")
     (directory/directory pid-dir :owner user :group group :mode "0755")
     (directory/directory log-dir :owner user :group group :mode "0755")
     (file/symbolic-link config-dir etc-config-dir))))

(defn hadoop-filesystem-dirs
  [request root]
  (let [owner (parameter/get-for-target [:hadoop :owner])
        group (parameter/get-for-target [:hadoop :group])]
    (directory/directory
     (str root "/hadoop") :owner owner :group group)
    (directory/directory
     (str root "/hadoop/logs") :owner owner :group group)
    (directory/directory
     (str root "/tmp") :owner owner :group group :mode "a+rwxt")))


(defn property->xml
  "Create a nested sequence representing the XML for a property"
  [property final]
  [:property
   (filter
    identity
    [[:name {} (name (key property))]
     [:value {} (val property)]
     (when final
       [:final {} "true"])])])

(def final-properties
  #{:dfs.block.size
    :dfs.data.dir
    :dfs.datanode.du.reserved
    :dfs.datanode.handler.count
    :dfs.hosts
    :dfs.hosts.exclude
    :dfs.name.dir
    :dfs.namenode.handler.count
    :dfs.permissions
    :fs.checkpoint.dir
    :fs.trash.interval
    :hadoop.tmp.dir
    :mapred.child.ulimit
    :mapred.job.tracker.handler.count
    :mapred.local.dir
    :mapred.tasktracker.map.tasks.maximum
    :mapred.tasktracker.reduce.tasks.maximum
    :tasktracker.http.threads
    :hadoop.rpc.socket.factory.class.default
    :hadoop.rpc.socket.factory.class.ClientProtocol
    :hadoop.rpc.socket.factory.class.JobSubmissionProtocol})

(defn properties->xml
  [properties]
  (prxml/prxml
   [:decl! {:version "1.0"}]
   [:configuration
    (map
     #(property->xml % (final-properties (key %)))
     properties)]))

(defn config-file
  [request properties]
  (let [config-dir (parameter/get-for-target [:hadoop :config-dir])
        log-dir (parameter/get-for-target [:hadoop :log-dir])
        owner (parameter/get-for-target [:hadoop :owner])
        group (parameter/get-for-target [:hadoop :group])]
    (->
     request
     (remote-file/remote-file
      (str config-dir "/hadoop-site.xml")
      :content (properties->xml properties)
      :owner owner :group group))))

(defn configure
  [request data-root name-node job-tracker {:as properties}]
  (let [config-dir (parameter/get-for-target [:hadoop :config-dir])
        pid-dir (parameter/get-for-target [:hadoop :pid-dir])
        log-dir (parameter/get-for-target [:hadoop :log-dir])
        env (str config-dir "/hadoop-env.sh")
        defaults
        {:ndfs.block.size 134217728
         :dfs.data.dir (str data-root "/hadoop/hdfs/data")
         :dfs.datanode.du.reserved 1073741824
         :dfs.datanode.handler.count 3
         :dfs.name.dir (str data-root "/hadoop/hdfs/name")
         :dfs.namenode.handler.count 5
         :dfs.permissions true
         :dfs.replication ""
         :fs.checkpoint.dir (str data-root "/hadoop/hdfs/secondary")
         :fs.default.name (format "hdfs://%s:8020/" name-node)
         :fs.trash.interval 1440
         :hadoop.tmp.dir (str data-root "/tmp/hadoop-${user.name}")
         :io.file.buffer.size 65536
         :mapred.child.java.opts "-Xmx550m"
         :mapred.child.ulimit 1126400
         :mapred.job.tracker (format "%s:8021" job-tracker)
         :mapred.job.tracker.handler.count 5
         :mapred.local.dir (str data-root "/hadoop/hdfs/mapred/local")
         :mapred.map.tasks.speculative.execution true
         :mapred.reduce.parallel.copies 10
         :mapred.reduce.tasks 10
         :mapred.reduce.tasks.speculative.execution false
         :mapred.submit.replication 10
         :mapred.system.dir (str data-root "/hadoop/hdfs/system/mapred")
         :mapred.tasktracker.map.tasks.maximum 2
         :mapred.tasktracker.reduce.tasks.maximum 1
         :tasktracker.http.threads 46
         :mapred.compress.map.output true
         :mapred.output.compression.type "BLOCK"
         :hadoop.rpc.socket.factory.class.default
         "org.apache.hadoop.net.StandardSocketFactory"
         :hadoop.rpc.socket.factory.class.ClientProtocol ""
         :hadoop.rpc.socket.factory.class.JobSubmissionProtocol ""
         :io.compression.codecs (str
                                 "org.apache.hadoop.io.compress.DefaultCodec,"
                                 "org.apache.hadoop.io.compress.GzipCodec")}
        properties (merge defaults properties)]
    (->
     request
     (hadoop-filesystem-dirs data-root)
     (config-file properties)
     (file/sed
      env
      {".*HADOOP_PID_DIR=.*" (str "export HADOOP_PID_DIR=" pid-dir)
       ".*HADOOP_SSH_OPTS=.*"
         (str "export HADOOP_SSH_OPTS=\"-o StrictHostKeyChecking=no\"")
       ".*HADOOP_OPTS=.*"
         "export HADOOP_OPTS=\"-Djava.net.preferIPv4Stack=true\""
       ".*HADOOP_LOG_DIR=.*"
         (str "export HADOOP_LOG_DIR=" log-dir "/logs")})
     (file/symbolic-link (str data-root "/hadoop/logs") (log-dir)))))

(script/defscript as-user [user & command])
(stevedore/defimpl as-user :default [user & command]
  (su -s "/bin/bash" - ~user -c ~@command))
(stevedore/defimpl as-user [#{:yum}] [user & command]
  ("/sbin/runuser" -s "/bin/bash" - ~user -c ~@command))

(defn- hadoop-service
  "Run a Hadoop service"
  [request hadoop-daemon description]
  (let [hadoop-home (parameter/get-for-target [:hadoop :home])
        hadoop-user (parameter/get-for-target [:hadoop :user])]
    (->
     request
     (exec-script/exec-checked-script
      (format "Start Hadoop %s" description)
      (as-user
       ~hadoop-user
       ~(str hadoop-home "/bin/hadoop-daemon.sh")
       ~hadoop-daemon)))))

(defn- hadoop-command
  "Run a Hadoop service"
  [request & args]
  (let [hadoop-home (parameter/get-for-target [:hadoop :home])
        hadoop-user (parameter/get-for-target [:hadoop :user])]
    (->
     request
     (exec-script/exec-checked-script
      (format "hadoop %s" args)
      (as-user
       ~hadoop-user
       ~(str hadoop-home "/bin/hadoop")
       ~@args)))))

(defn name-node
  "Run a Hadoop name node"
  [request data-dir]
  (->
   request
   (hadoop-service "namenode" "Name Node")
   (hadoop-command "dfsadmin" "-safemode" "wait")
   (hadoop-command "fs" "-mkdir" "/user")
   (hadoop-command "fs" "-chmod" "+w" "/user")))

(defn secondary-name-node
  "Run a Hadoop secondary name node"
  [request]
  (hadoop-service request "secondarynamenode" "secondary name node"))

(defn job-tracker
  "Run a Hadoop job tracker"
  [request]
  (-> request
      (hadoop-service "jobtracker" "job tracker")
      (parameter/assoc-for-service
       [:hadoop :mapred :job-tracker]
       (format "%s:8021" (request-map/target-ip)))))

(defn data-node
  "Run a Hadoop data node"
  [request]
  (-> request
      (hadoop-service "datanode" "data node")
      (parameter/assoc-for-service
       [:hadoop :fs :default-name]
       (format "hdfs://%s:8020" (request-map/target-ip)))))

(defn task-tracker
  "Run a Hadoop task tracker"
  [request]
  (hadoop-service request "tasktracker" "task tracker"))



#_
(pallet.core/defnode)
