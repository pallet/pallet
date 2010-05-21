(ns #^{:author "Hugo Duncan"}
  pallet.chef
  "Execute chef recipes using chef solo."
  (:use
   [org.jclouds.compute
    :only [hostname public-ips private-ips tag nodes *compute*]]
   [pallet.compute :only [primary-ip nodes-by-tag ssh-port]]
   [pallet.core :only [nodes-in-set]]
   [pallet.utils
    :only [*admin-user* remote-sudo system pprint-lines quoted sh-script]]
   clojure.contrib.logging)
  (:require
   [clojure.contrib.io :as io]
   [clojure.contrib.java-utils :as java-utils]))

(def *chef-repository* nil)
(defmacro with-chef-repository
  "Specifies the chef repository that contains the cookboks and configurations"
  [path & body]
  `(binding [*chef-repository* ~path]
     ~@body))


(def *remote-chef-path* "/var/lib/chef/")
(defmacro with-remote-chef-path
  "Specifies the path to use for the chef cookbooks on the remote machine."
  [path & body]
    `(binding [*remote-chef-path* ~path]
     ~@body))

;;; Provisioning
(defn rsync-repo [from to user port]
  (info (str "rsyncing chef repository to " to))
  (let [ssh (str "/usr/bin/ssh -o \"StrictHostKeyChecking no\" "
                 (if port (str "-p " port " ")))
        cmd (str "/usr/bin/rsync "
                 "-e '" ssh "' "
                 " -rP --delete --copy-links -F -F "
                 from  " " (:username user) "@" to ":" *remote-chef-path*)]
    (sh-script cmd)))

(defn rsync-node
  ([node user]
     (rsync-node node user *chef-repository*))
  ([node user chef-repository]
     (if (primary-ip node)
       (rsync-repo chef-repository (primary-ip node) user (ssh-port node)))))

;;; Chef recipe attribute output
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
    (apply str (interpose "\n" (map output-tag (nodes-by-tag nodes))))))

(def node-cookbook "compute-nodes")

(defn output-attributes
  "Writes a desciption of nodes to a chef repository"
  ([nodes]
     (output-attributes nodes *chef-repository*))
  ([nodes chef-repository]
     (io/with-out-writer
       (java-utils/file
        chef-repository "site-cookbooks" node-cookbook
        "attributes" (str node-cookbook ".rb"))
       (println (ips-to-rb nodes)))))

;;; Chef invocation
(defn chef-cook-solo
  "Run a chef solo command on a server.  A command is expected to exist as
   chef-repo/config/command.json"
  ([server command user]
     (chef-cook-solo server command user *remote-chef-path*))
  ([server command user remote-chef-path]
     (info (str "chef-cook-solo " server))
     (let [resp
           (remote-sudo
            (str server)
            (str "chef-solo -c " remote-chef-path "config/solo.rb -j "
                 remote-chef-path "config/" command ".json")
            user)]
        (if (not (zero? (resp :exit)))
          (println "CHEF FAILED -------------------------------")))))

(defn chef-cook [node user]
  (if (primary-ip node)
    (chef-cook-solo (primary-ip node) (tag node) user)))

(defn cook-node
  "Run chef on the specified node."
  [node chef-repository user]
  (rsync-node node user chef-repository)
  (chef-cook node user))

(defn cook
  ([nodes]
     (cook nodes *chef-repository* *compute* *admin-user*))
  ([nodes chef-repository]
     (cook nodes chef-repository *compute* *admin-user*))
  ([nodes chef-repository compute]
     (cook nodes chef-repository compute *admin-user*))
  ([nodes chef-repository compute user]
     (doseq [node (nodes-in-set nodes compute)]
       (cook-node node chef-repository user))))
