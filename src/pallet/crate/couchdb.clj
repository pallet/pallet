(ns pallet.crate.couchdb
 (:use
  [pallet.stevedore :only [script]])
 (:require
   pallet.target
   [pallet.resource.package :as package]
   [pallet.resource.exec-script :as exec-script]
   [pallet.stevedore :as script]
   [pallet.resource.service :as service]
   [clojure.contrib.json :as json]))


(defn install
  "Installs couchdb, leaving all default configuration as-is.  Use the `couchdb`
   fn to install and configure."
  []
  (package/package "couchdb")
  ;; this fixes this problem
  ;; https://bugs.launchpad.net/ubuntu/karmic/+source/couchdb/+bug/448682
  ;; apparently impacts centos, too.  The fix implemented here pulled from
  ;; http://www.mail-archive.com/user@couchdb.apache.org/msg05488.html
  ;; I added the /var/run/couchdb dirs, which seemed to be tripping up service
  ;; stops and restarts.  The specified paths *should* cover the
  ;; common/reasonable locations....
  (doseq [dir ["/usr/local/var/log/couchdb" "/var/log/couchdb"
               "/usr/local/var/run/couchdb" "/var/run/couchdb"
               "/usr/local/var/lib/couchdb" "/var/lib/couchdb"
               "/usr/local/etc/couchdb" "/etc/couchdb"]]
    (exec-script/exec-script
     (script/script
      (if (file-exists? ~dir)
        ~(format "chown -R couchdb:couchdb %s && chmod 0770 %s" dir dir))))))

(defn couchdb
  "Ensures couchdb is installed (along with curl, as a basis for convenient
   configuration) optionally configuring it as specified.

   e.g. (couchdb
          [:query_server_config :reduce_limit] \"false\"
          [:couchdb :database_dir] \"/var/some/other/path\")

   Note that the configuration options mirror the couchdb ini file hierarchy
   documented here: http://wiki.apache.org/couchdb/Configurationfile_couch.ini

   If any options are provided, then the couch server will be restarted after
   the configuraiton is modified."
  [& option-keyvals]
  (install)
  (when (seq option-keyvals)
    (package/package "curl")
    (service/service "couchdb" :action :start)
    ;; the sleeps are here because couchdb doesn't actually start taking
    ;; requests for a little bit -- it appears that the real process that's
    ;; forked off is beam which ramps up couchdb *after* it's forked.
    (exec-script/exec-script (script (sleep 2)))
    (doseq [[k v] (apply hash-map option-keyvals)
            :let [config-path (->> k
                                   (map #(if (string? %) % (name %)))
                                   (interpose "/"))
                  url (apply str "http://localhost:5984/_config/" config-path)
                  v-json (str \' (json/json-str v) \')]]
      (exec-script/exec-script
       (script (curl -X PUT -d ~v-json ~url))))
    (service/service "couchdb" :action :restart)
    (exec-script/exec-script
     (script
      (sleep 2)
      (echo "Checking that couchdb is alive...")
      (curl "http://localhost:5984")))))
