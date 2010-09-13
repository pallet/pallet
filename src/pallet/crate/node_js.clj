(ns pallet.crate.node-js
  "Install and configure node.js"
  (:require
   [pallet.resource.package :as package]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.remote-directory :as remote-directory]))

(def src-dir "/opt/local/node-js")

(def md5s {"0.2.1" nil})

(defn tarfile
  [version]
  (format "node-v%s.tar.gz" version))

(defn download-path [version]
  (format "http://nodejs.org/dist/%s" (tarfile version)))

(defn install
  [request & {:keys [version] :or {version "0.2.1"}}]
  (->
   request
   (package/packages
    :yum ["gcc" "glib" "glibc-common" "python"]
    :aptitude ["build-essential" "python" "libssl-dev"])
   (remote-directory/remote-directory
    src-dir :url (download-path version) :md5 (md5s version) :unpack :tar)
   (exec-script/exec-checked-script
    "Build node-js"
    (cd ~src-dir)
    ("./configure")
    (make)
    (make install))))

#_
(pallet.core/defnode a {}
  :bootstrap (pallet.resource/phase
              (pallet.crate.automated-admin-user/automated-admin-user))
  :configure (pallet.resource/phase
              (pallet.crate.node-js/install)
              (pallet.crate.upstart/package)
              (pallet.resource.remote-file/remote-file
               "/tmp/node.js"
               :content "
var sys = require(\"sys\"),
    http = require(\"http\");

http.createServer(function(request, response) {
    response.sendHeader(200, {\"Content-Type\": \"text/html\"});
    response.write(\"Hello World!\");
    response.close();
}).listen(8080);

sys.puts(\"Server running at http://localhost:8080/\");"
               :literal true)
              (pallet.crate.upstart/job
               "nodejs"
               :script "export HOME=\"/home/duncan\"
    exec sudo -u duncan /usr/local/bin/node /tmp/node.js 2>&1 >> /var/log/node.log"
               :start-on "startup"
               :stop-on "shutdown")))
