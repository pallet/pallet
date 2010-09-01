(ns pallet.crate.zookeeper
  (:require
   [pallet.arguments :as arguments]
   [pallet.compute :as compute]
   [pallet.resource :as resource]
   [pallet.parameter :as parameter]
   [pallet.target :as target]
   [pallet.resource.remote-directory :as remote-directory]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.directory :as directory]
   [pallet.resource.file :as file]
   [pallet.resource.service :as service]
   [pallet.resource.user :as user]
   [pallet.stevedore :as stevedore]
   [clojure.string :as string]))

(def install-path "/usr/local/zookeeper")
(def log-path "/var/log/zookeeper")
(def tx-log-path (format "%s/txlog" log-path))
(def config-path "/etc/zookeeper")
(def data-path "/var/zookeeper")
(def zookeeper-home install-path)
(def zookeeper-user "zookeeper")
(def zookeeper-group "zookeeper")
(def default-config
  {:dataDir data-path
   :tickTime 2000
   :clientPort 2181
   :initLimit 10
   :syncLimit 5
   :dataLogDir tx-log-path})


(defn url "Download url"
  [version]
  (format
   "http://www.apache.org/dist/hadoop/zookeeper/zookeeper-%s/zookeeper-%s.tar.gz"
   version version))

(defn install
  "Install Zookeeper"
  [& {:keys [user group version]
      :or {user zookeeper-user
           group zookeeper-group
           version "3.3.1"}
      :as options}]
  (let [url (url version)]
    (resource/parameters
     [:zookeeper :home] (format "%s-%s" install-path version)
     [:zookeeper :owner] user
     [:zookeeper :group] group)
    (user/user user :system true)
    (user/group group :system true)
    (remote-directory/remote-directory
     (parameter/lookup :zookeeper :home)
     :url url
     :md5-url (str url ".md5")
     :unpack :tar
     :tar-options "xz"
     :owner user
     :group group)
    (directory/directory
     log-path
     :owner user
     :group group
     :mode "0755")
    (directory/directory
     tx-log-path
     :owner user
     :group group
     :mode "0755")
    (directory/directory
     config-path
     :owner user
     :group group
     :mode "0755")
    (directory/directory
     data-path
     :owner user
     :group group
     :mode "0755")
    (remote-file/remote-file
     (format "%s/log4j.properties" config-path)
     :remote-file (arguments/delayed
                   (format "%s/conf/log4j.properties"
                           (parameter/get-for [:zookeeper :home])))
     :owner user
     :group group
     :mode "0644")
    (file/sed
     (format "%s/log4j.properties" config-path)
     {"log4j.rootLogger=INFO, CONSOLE"
      "log4j.rootLogger=INFO, ROLLINGFILE"
      "log4j.appender.ROLLINGFILE.File=zookeeper.log"
      (format "log4j.appender.ROLLINGFILE.File=%s/zookeeper.log" log-path)}
     {:seperator "|"})))

(defn init
  [& options]
  (let [options (apply hash-map options)]
    (service/init-script
     "zookeeper"
     :link (arguments/delayed
            (format
             "%s/bin/zkServer.sh" (parameter/get-for [:zookeeper :home]))))
    (if-not (:no-enable options)
      (service/service
       "zookeeper" :action :start-stop
       :sequence-start "20 2 3 4 5"
       :sequence-stop "20 0 1 6"))))

(defn config-files*
  "Create a zookeeper configuration file.  We sort by name to preserve sequence
   across invocations."
  []
  (let [target-name (target/target-name)
        nodes (sort-by #(.getName %) (target/nodes-in-tag))
        configs (parameter/get-for [:zookeper (keyword (target/tag))])
        config (configs (keyword target-name))]
    (stevedore/do-script
     (remote-file/remote-file*
      (format "%s/zoo.cfg" config-path)
      :content (str (string/join
                     \newline
                     (map #(format "%s=%s" (name (first %)) (second %))
                          (merge
                           default-config
                           (dissoc config :electionPort :quorumPort))))
                    \newline
                    (string/join
                     \newline
                     (map #(let [config (configs (keyword (.getName %1)))]
                             (format "server.%s=%s:%s:%s"
                                     %2
                                     (compute/primary-ip %1)
                                     (:quorumPort config 2888)
                                     (:electionPort config 3888)))
                          nodes
                          (range 1 (inc (count nodes))))))
      :owner (parameter/get-for [:zookeeper :owner])
      :group (parameter/get-for [:zookeeper :group])
      :mode "0644")

     (remote-file/remote-file*
      (format "%s/myid" data-path)
      :content (str (some #(and (= target-name (second %)) (first %))
                          (map #(vector %1 (.getName %2))
                               (range 1 (inc (count nodes)))
                               nodes)))
      :owner (parameter/get-for [:zookeeper :owner])
      :group (parameter/get-for [:zookeeper :group])
      :mode "0644"))))

(resource/defresource config-files
  config-files* [])

(defn store-configuration*
  [options]
  (parameter/update-default!
   [:default :zookeper (keyword (target/tag))]
   (fn [m]
     (assoc m (target/target-name) options))))

(resource/deflocal store-configuration
  "Capture zookeeper configuration"
  store-configuration* [& options])

(defn configure
  "Configure zookeeper instance"
  [& {:keys [dataDir tickTime clientPort initLimit syncLimit dataLogDir
             electionPort quorumPort]
      :or {quorumPort 2888 electionPort 3888}
      :as options}]
  (resource/execute-pre-phase
   (store-configuration
    (assoc options :quorumPort quorumPort :electionPort electionPort)))
  (config-files))

#_
(pallet.core/defnode zk
  []
  :bootstrap [(pallet.crate.automated-admin-user/automated-admin-user)]
  :configure [(pallet.crate.java/java :openjdk :jdk)
              (pallet.crate.zookeeper/install)
              (pallet.crate.zookeeper/configure)
              (pallet.crate.zookeeper/init)]
 :restart-zookeeper [(pallet.resource.service/service
                      "zookeeper" :action :restart)])
