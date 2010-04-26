(ns pallet.crate.cloudkick
  "Agent install for cloudkick"
  (:require pallet.resource.remote-file)
  (:require
   [pallet.resource.package :as package]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.hostinfo :as hostinfo]))

(def cloudkick-conf-template "crate/cloudkick/cloudkick.conf")

(defn cloudkick
  "Install cloudkick agent.  Options are:
     :name string     - name to identify node in cloudkick
     :tags seq        - tags for grouping nodes in cloudkick
     :resources       - proxy to port 80 on agent-resources.cloudkick.com
     :endpoint        - proxy to port 4166 on agent-endpoint.cloudkick.com"
  [nodename oauth-key oauth-secret & options]
  (remote-file/remote-file
   "/etc/cloudkick.conf"
   :template cloudkick-conf-template
   :values (merge {:oauth-key oauth-key :oauth-secret oauth-secret
                   :name nodename :tags ["any"] :resources nil :endpoint nil}
                  (apply hash-map options)))
  (package/package-source
   "cloudkick"
   :aptitude {:url "http://packages.cloudkick.com/ubuntu"
              :key-url "http://packages.cloudkick.com/cloudkick.packages.key"}
   :yum { :url (str "http://packages.cloudkick.com/redhat/"
                    (hostinfo/architecture))})
  (package/package-manager :update)
  (package/package "cloudkick-agent"))


