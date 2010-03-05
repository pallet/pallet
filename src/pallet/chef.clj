(ns pallet.chef
  (:use [org.jclouds.compute :only [hostname public-ips private-ips tag nodes]]
        [pallet.compute :only [primary-ip nodes-by-tag]]
        [pallet.utils :only [remote-sudo system pprint-lines quoted]]
        [pallet.core :only [*admin-user*]]
        [clojure.contrib.java-utils :only [file]]
        [clojure.contrib.duck-streams :only [with-out-writer]]
        clojure.contrib.logging))

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

;;; Provisioning
(defn rsync-repo [from to user]
  (info (str "rsyncing chef repository to " to))
  (let [cmd (str "/usr/bin/rsync -rP --delete --copy-links -F -F "
                 from  " " (:username user) "@" to ":" *remote-chef-path*)]
    (system cmd)))

(defn rsync-node
  ([node user]
     (rsync-node node user *chef-repository*))
  ([node user chef-repository]
     (if (primary-ip node)
       (rsync-repo chef-repository (primary-ip node) user))))

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
     (with-out-writer (file chef-repository "site-cookbooks" node-cookbook
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
     (let [[resp status]
            (remote-sudo
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
  [node user chef-repository]
  (rsync-node node user chef-repository)
  (chef-cook node user))

(defn cook-nodes
  ([nodes] (cook-nodes nodes *admin-user* *chef-repository*))
  ([nodes user] (cook-nodes nodes user *chef-repository*))
  ([nodes user chef-repository]
     (dorun (map #(cook-node % user chef-repository) nodes))))

(defn configure-with-chef
  "Generates a function that may me be used to run chef on a sequence of nodes"
  ([] (configure-with-chef *admin-user* *chef-repository*))
  ([user] (configure-with-chef user *chef-repository*))
  ([user repository-path]
     (fn [compute new-nodes]
       (let [nodes (nodes compute)]
         (output-attributes nodes repository-path)
         (cook-nodes nodes user repository-path)
         nodes))))
