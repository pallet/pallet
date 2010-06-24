(ns pallet.crate.nginx
  "Crate for nginx management functions"
  (:require
   [pallet.target :as target]
   [pallet.resource :as resource]
   [pallet.stevedore :as stevedore]
   [pallet.utils :as utils]
   [pallet.template :as template]
   [pallet.strint :as strint]
   [pallet.resource.exec-script :as exec-script]
   [pallet.resource.package :as package]
   [pallet.resource.user :as user]
   [pallet.resource.service :as service]
   [pallet.resource.remote-file :as remote-file]
   [pallet.resource.file :as file]
   [pallet.resource.directory :as directory]
   [clojure.contrib.string :as string]))

(def src-packages
     ["libpcre3" "libpcre3-dev" "libssl" "libssl-dev"])

(def nginx-md5s
     {"0.7.65" "abc4f76af450eedeb063158bd963feaa"})

(defn ftp-path [version]
  (format "http://sysoev.ru/nginx/nginx-%s.tar.gz" version))

(def nginx-conf-dir "/etc/nginx")
(def nginx-install-dir "/opt/nginx")
(def nginx-log-dir "/var/log/nginx")
(def nginx-pid-dir "/var/run/nginx")
(def nginx-lock-dir "/var/lock")
(def nginx-user "www-data")
(def nginx-group "www-data")
(def nginx-binary "/usr/sbin/nginx")

(def nginx-init-script "crate/nginx/nginx")
(def nginx-conf "crate/nginx/nginx.conf")
(def nginx-site "crate/nginx/site")
(def nginx-location "crate/nginx/location")

(def nginx-defaults
     {:version "0.7.65"
      :modules [:http_ssl_module :http_gzip_static_module]})

(def nginx-default-conf
     {:gzip "on"
      :gzip_http_version "1.0"
      :gzip_comp_level "2"
      :gzip_proxied "any"
      :gzip_types ["text/plain"
                   "text/html"
                   "text/css"
                   "application/x-javascript"
                   "text/xml"
                   "application/xml"
                   "application/xml+rss"
                   "text/javascript"]
      :client_max_body_size "10M"
      :sendfile "on"
      :tcp_nopush "off"
      :tcp_nodelay "off"
      :keepalive "on"
      :keepalive_timeout 65
      :worker_processes "total"
      :worker_connections 2048
      :server_names_hash_bucket_size 64})

(def nginx-default-site
     {:listen "80"
      :server_name "localhost"
      :access_log (str nginx-log-dir "/access.log")})

(def nginx-default-location
     {:location "/"
      :root nil
      :index ["index.html" "index.htm"]
      :proxy_pass nil})


(defn nginx
  "Install nginx from source. Options:
     :version version-string   -- specify the version (default \"0.7.65\")
     :configuration map        -- map of values for nginx.conf"
  [& options]
  (let [options (merge nginx-defaults (apply hash-map options))
        version (options :version)
        modules (options :version)
        basename (str "nginx-" version)
        tarfile (str basename ".tar.gz")
        tarpath (str (stevedore/script (tmp-dir)) "/" tarfile)]
    (doseq [p src-packages]
      (package/package p))
    (remote-file/remote-file
     tarpath :url (ftp-path version) :md5 (get nginx-md5s version "x"))
    (user/user
     nginx-user
     :home nginx-install-dir :shell :false :create-home true :system true)
    (directory/directory nginx-install-dir :owner "root")
    (exec-script/exec-script
     (stevedore/script
      (cd ~nginx-install-dir)
      (tar xz --strip-components=1 -f ~tarpath)
      ("./configure"
       ~(format "--conf-path=%s/nginx.conf" nginx-conf-dir)
       ~(format "--prefix=%s" nginx-install-dir)
       ~(format "--pid-path=%s/nginx.pid" nginx-pid-dir)
       ~(format "--error-log-path=%s/error.log" nginx-log-dir)
       ~(format "--http-log-path=%s/access.log" nginx-log-dir)
       ~(format "--lock-path=%s/nginx" nginx-lock-dir)
       "--sbin-path=/usr/local/sbin"
       ~(apply
         str
         (map #(format "--with-%s " (utils/as-string %)) (options :modules))))
      (make)
      (make install)))
    (remote-file/remote-file
     (format "%s/nginx.conf" nginx-conf-dir)
     :template nginx-conf
     :values (reduce
              merge {}
              [nginx-default-conf
               (options :configuration)
               (strint/capture-values nginx-user nginx-group)])
     :owner "root" :group nginx-group :mode "0644")))


(defn init*
  [& options]
  (service/init-script*
     "nginx"
     :content (utils/load-resource-url
               (template/find-template
                nginx-init-script
                (target/node-type)))
     :literal true))

(resource/defresource init
  "Creates a nginx init script."
  init* [])


(defn site*
  [name & options]
  (let [options (apply hash-map options)
        available (format "%s/sites-available/%s" nginx-conf-dir name)
        enabled (format "%s/sites-enabled/%s" nginx-conf-dir name)
        site (fn [filename]
               (let [locations (string/join
                                \newline
                                (map
                                 #(pallet.template/interpolate-template
                                   nginx-location (merge nginx-default-location %))
                                 (:locations options)))]
                 (remote-file/remote-file*
                  filename
                  :template nginx-site
                  :values (utils/map-with-keys-as-symbols
                            (reduce
                             merge {:locations locations}
                             [nginx-default-site
                              (dissoc options :locations)])))))]
    (stevedore/do-script
     (directory/directory* (format "%s/sites-available" nginx-conf-dir))
     (directory/directory* (format "%s/sites-enabled" nginx-conf-dir))
     (condp = (get options :action :enable)
       :enable (stevedore/do-script
                (site enabled)
                (file/file* available :action :delete :force true))
       :disable (stevedore/do-script
                 (site available)
                 (file/file* enabled :action :delete :force true))
       :remove (stevedore/do-script
                (file/file* available :action :delete :force true)
                (file/file* enabled :action :delete :force true))))))

(resource/defresource site
  "Enable or disable a site.  Options:
:listen        -- address to listen on
:server_name   -- name
:locations     -- locations (a seq of maps, with keys :location, :root
                  :index, :proxy_pass)"
  site* [site-name & options])
