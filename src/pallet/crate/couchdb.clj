(ns pallet.crate.couchdb
 (:use [pallet.stevedore :only [script]])
 (:require
   [pallet.resource.package :as package]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.service :as service]
   [clojure.contrib.json.write :as json]))

(defn couchdb
  "Installs couchdb (and curl, as a basis for convenient configuration), optionally
   configuring it as specified.

   e.g. (couchdb [:query_server_config :reduce_limit] \"false\"
          [:couchdb :database_dir] \"/var/some/other/path\")

   Note that the configuration options mirror the couchdb ini file hierarchy,
   documented here: http://wiki.apache.org/couchdb/Configurationfile_couch.ini

   If any options are provided, then the couch server will be restarted after
   the configuraiton is modified."
  [& option-keyvals]
  (package/package "couchdb")
  (when (seq option-keyvals)
    (package/package "curl")
    (service/service "couchdb" :action :start)
    (doseq [[k v] (apply hash-map option-keyvals)
            :let [config-path (->> k
                                (map #(if (string? %) % (name %)))
                                (interpose "/"))
                  url (apply str "http://localhost:5984/_config/" config-path)
                  v-json (str \' (json/json-str v) \')]]
      (exec-script/exec-script
        (script (curl -X PUT -d ~v-json ~url))))
    (service/service "couchdb" :action :restart)))