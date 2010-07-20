(ns pallet.crate.zookeeper
  (:require
   [pallet.arguments :as arguments]
   [pallet.resource :as resource]
   [pallet.parameter :as parameter]
   [pallet.resource.remote-directory :as remote-directory]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.directory :as directory]
   [pallet.resource.file :as file]
   [pallet.resource.service :as service]
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
     {"dataDir" data-path
      "tickTime" 2000
      "clientPort" 2181
      "initLimit" 10
      "syncLimit" 5
      "dataLogDir" tx-log-path})


(defn url "Download url"
  [version]
  (format
   "http://www.apache.org/dist/hadoop/zookeeper/zookeeper-%s/zookeeper-%s.tar.gz"
   version version))

(defn install
  "Install Zookeeper"
  [& options]
  (let [options (apply hash-map options)
        version (options :version "3.3.1")
        url (url version)]
    (resource/parameters
     [:zookeeper :home] (format "%s-%s" install-path version)
     [:zookeeper :owner] (:user options zookeeper-user)
     [:zookeeper :group] (:group options zookeeper-group))
    (remote-directory/remote-directory
     (parameter/lookup :zookeeper :home)
     :url url
     :md5-url (str url ".md5")
     :unpack :tar
     :tar-options "xz"
     :owner (parameter/lookup :zookeeper :user)
     :group (parameter/lookup :zookeeper :group))
    (directory/directory
     log-path
     :owner (parameter/lookup :zookeeper :user)
     :group (parameter/lookup :zookeeper :group)
     :mode "0755")
    (directory/directory
     tx-log-path
     :owner (parameter/lookup :zookeeper :user)
     :group (parameter/lookup :zookeeper :group)
     :mode "0755")
    (directory/directory
     config-path
     :owner (parameter/lookup :zookeeper :user)
     :group (parameter/lookup :zookeeper :group)
     :mode "0755")
    (directory/directory
     data-path
     :owner (parameter/lookup :zookeeper :user)
     :group (parameter/lookup :zookeeper :group)
     :mode "0755")
    (remote-file/remote-file
     (format "%s/log4j.properties" config-path)
     :remote-file (arguments/delayed
                   (format "%s/conf/log4j.properties"
                           (parameter/get-for [:zookeeper :home])))
     :owner (parameter/lookup :zookeeper :user)
     :group (parameter/lookup :zookeeper :group)
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
            (format "%s/bin/zkServer.sh" (parameter/get-for [:zookeeper :home]))))
    (if-not (:no-enable options)
      (service/service
       "zookeeper" :action :start-stop
       :sequence-start "20 2 3 4 5"
       :sequence-stop "20 0 1 6"))))

(defn configure
  "Create a zookeeper configuration file"
  [& options]
  (let [options (apply hash-map options)]
    (remote-file/remote-file
     (format "%s/zoo.cfg" config-path)
     :content (str (string/join
                    \newline
                    (map #(format "%s=%s" (first %) (second %))
                         (merge default-config (dissoc options :servers))))
                   (string/join
                    \newline
                    (map #(format "server.%s=zoo%s:%s:%s"
                                  (:id %)
                                  (:quorum-port % 2888)
                                  (:election-port % 3888))
                         (:servers options))))
     :owner (parameter/lookup :zookeeper :user)
     :group (parameter/lookup :zookeeper :group)
     :mode "0644")))

(defn id
  "Create a zookeeper id file"
  [id]
  (remote-file/remote-file
   (format "%s/myid" data-path)
   :content (str id)
   :owner (parameter/lookup :zookeeper :user)
   :group (parameter/lookup :zookeeper :group)
   :mode "0644"))
